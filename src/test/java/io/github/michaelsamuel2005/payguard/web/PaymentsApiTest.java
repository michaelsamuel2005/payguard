package io.github.michaelsamuel2005.payguard.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.michaelsamuel2005.payguard.adapters.directory.SimulatedAccountNameDirectory;
import io.github.michaelsamuel2005.payguard.adapters.directory.SimulatedAccountNameDirectory.FailureMode;
import io.github.michaelsamuel2005.payguard.adapters.resilience.ResilientAccountNameDirectory;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * The whole application over HTTP (MockMvc) on a real PostgreSQL, with real signed tokens. Needs
 * SPRING_DATASOURCE_URL; CI starts PostgreSQL as a service container and sets it.
 */
@SpringBootTest(properties = {"payguard.rate-limit.payments-per-minute=5", "payguard.directory.timeout=300ms"})
@AutoConfigureMockMvc
@Import(TestJwt.Config.class)
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL", matches = ".+")
class PaymentsApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    SimulatedAccountNameDirectory directory;
    @Autowired
    ResilientAccountNameDirectory resilient;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    private String customer;

    @BeforeEach
    void newCustomer() {
        customer = "cust-" + UUID.randomUUID();
    }

    @AfterEach
    void healTheDirectory() {
        directory.setFailureMode(FailureMode.NONE);
        resilient.reset();
    }

    @Test
    void a_usual_payment_to_a_payee_whose_name_matches_is_released() throws Exception {
        JsonNode payee = registerPayee("John Smith", "12-34-56", "12345678");
        assertThat(payee.get("nameCheck").asText()).isEqualTo("MATCH");
        assertThat(payee.get("accountNumber").asText()).isEqualTo("****5678");
        assertThat(payee.has("accountName")).isFalse();
        pay(payee.get("id").asText(), 2_500, "DINNER", key())
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.warning").doesNotExist());
    }

    @Test
    void a_large_payment_to_a_name_that_does_not_match_is_held_until_a_reviewer_approves_it() throws Exception {
        JsonNode payee = registerPayee("Mike Jones", "20-30-40", "33334444");
        assertThat(payee.get("nameCheck").asText()).isEqualTo("NO_MATCH");
        assertThat(payee.has("accountName")).isFalse();
        String id = body(pay(payee.get("id").asText(), 400_000, "INVOICE 77", key())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.warning.code").value("NAME_MISMATCH"))).get("id").asText();

        String reviewer = TestJwt.bearer("rita-" + UUID.randomUUID(), "reviews");
        mvc.perform(get("/v1/reviews").param("limit", "200").header(AUTHORIZATION, reviewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(id)));
        mvc.perform(post("/v1/reviews/" + id).header(AUTHORIZATION, reviewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("decision", "APPROVE",
                                "reason", "invoice confirmed with the supplier on a known number"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
        mvc.perform(get("/v1/payments/" + id + "/audit").header(AUTHORIZATION, as("payments")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].action").value("INITIATED"))
                .andExpect(jsonPath("$[1].action").value("APPROVED"));
    }

    @Test
    void a_close_match_shows_the_real_name_and_the_customer_must_acknowledge_the_warning() throws Exception {
        JsonNode payee = registerPayee("Jon Smith", "12-34-56", "12345678");
        assertThat(payee.get("nameCheck").asText()).isEqualTo("CLOSE_MATCH");
        assertThat(payee.get("accountName").asText()).isEqualTo("JOHN SMITH");
        String id = body(pay(payee.get("id").asText(), 2_000, "GIFT", key())
                .andExpect(jsonPath("$.status").value("AWAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.warning.code").value("NAME_CLOSE_MATCH"))).get("id").asText();
        confirm(id, "NEW_PAYEE").andExpect(status().isBadRequest());
        confirm(id, "NAME_CLOSE_MATCH").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RELEASED"));
        confirm(id, "NAME_CLOSE_MATCH").andExpect(status().isConflict());
    }

    @Test
    void a_retry_with_the_same_key_returns_the_same_payment_and_a_different_body_is_refused() throws Exception {
        String payeeId = registerPayee("John Smith", "12-34-56", "12345678").get("id").asText();
        String k = key();
        String first = body(pay(payeeId, 2_500, "DINNER", k).andExpect(status().isCreated())).get("id").asText();
        pay(payeeId, 2_500, "DINNER", k)
                .andExpect(status().isOk())
                .andExpect(header().string(PaymentController.REPLAYED, "true"))
                .andExpect(jsonPath("$.id").value(first));
        pay(payeeId, 9_999, "DINNER", k)
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")));
    }

    @Test
    void malformed_requests_get_a_400_problem() throws Exception {
        String payeeId = registerPayee("John Smith", "12-34-56", "12345678").get("id").asText();
        mvc.perform(post("/v1/payments").header(AUTHORIZATION, as("payments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("payeeId", payeeId, "amountPence", 100,
                                "reference", "X"))))
                .andExpect(status().isBadRequest());
        pay(payeeId, 0, "X", key()).andExpect(status().isBadRequest());
        pay("not-a-uuid", 100, "X", key()).andExpect(status().isBadRequest());
        pay(payeeId, 100, "<script>", key()).andExpect(status().isBadRequest());
        mvc.perform(post("/v1/payees").header(AUTHORIZATION, as("payments"))
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/payees").header(AUTHORIZATION, as("payments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Ann", "sortCode", "12-34",
                                "accountNumber", "12345678"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("sort code")));
    }

    @Test
    void tokens_are_checked_for_signature_expiry_and_scope() throws Exception {
        mvc.perform(get("/v1/payees")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/payees").header(AUTHORIZATION, TestJwt.expired(customer, "payments")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/payees").header(AUTHORIZATION, TestJwt.signedByAStranger(customer, "payments")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/payees").header(AUTHORIZATION, as("reviews"))).andExpect(status().isForbidden());
        mvc.perform(get("/v1/reviews").header(AUTHORIZATION, as("payments"))).andExpect(status().isForbidden());
        mvc.perform(get("/v1/payees").header(AUTHORIZATION, as("payments"))).andExpect(status().isOk());
    }

    @Test
    void a_customer_cannot_see_another_customers_payee_or_payment() throws Exception {
        String payeeId = registerPayee("John Smith", "12-34-56", "12345678").get("id").asText();
        String id = body(pay(payeeId, 1_000, "LUNCH", key()).andExpect(status().isCreated())).get("id").asText();
        String stranger = TestJwt.bearer("cust-" + UUID.randomUUID(), "payments");
        mvc.perform(get("/v1/payments/" + id).header(AUTHORIZATION, stranger)).andExpect(status().isNotFound());
        mvc.perform(get("/v1/payments/" + id + "/audit").header(AUTHORIZATION, stranger))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/payees/" + payeeId).header(AUTHORIZATION, stranger)).andExpect(status().isNotFound());
    }

    @Test
    void a_reviewer_cannot_decide_their_own_payment() throws Exception {
        String payeeId = registerPayee("Mike Jones", "20-30-40", "33334444").get("id").asText();
        String id = body(pay(payeeId, 400_000, "INVOICE 9", key()).andExpect(status().isCreated())).get("id").asText();
        mvc.perform(post("/v1/reviews/" + id).header(AUTHORIZATION, TestJwt.bearer(customer, "payments reviews"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("decision", "APPROVE", "reason", "it is mine"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void when_the_name_check_is_down_payments_are_warned_and_the_breaker_opens() throws Exception {
        directory.setFailureMode(FailureMode.DOWN);
        for (int i = 0; i < 5; i++) {
            JsonNode p = registerPayee("John Smith", "12-34-56", String.format(Locale.ROOT, "1234%04d", i));
            assertThat(p.get("nameCheck").asText()).isEqualTo("UNAVAILABLE");
        }
        assertThat(resilient.state()).isEqualTo(CircuitBreaker.State.OPEN);
        JsonNode payee = registerPayee("Priya Patel", "20-30-40", "33334444");
        assertThat(payee.get("nameCheck").asText()).isEqualTo("UNAVAILABLE");
        pay(payee.get("id").asText(), 1_000, "LUNCH", key())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AWAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.warning.code").value("NAME_CHECK_UNAVAILABLE"));
    }

    @Test
    void a_slow_name_check_times_out_and_fails_safe() throws Exception {
        directory.setFailureMode(FailureMode.SLOW);
        long start = System.nanoTime();
        JsonNode payee = registerPayee("John Smith", "12-34-56", "12345678");
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(payee.get("nameCheck").asText()).isEqualTo("UNAVAILABLE");
        assertThat(millis).isLessThan(3_000);
    }

    @Test
    void starting_payments_too_fast_gets_429_with_retry_after() throws Exception {
        String payeeId = registerPayee("John Smith", "12-34-56", "12345678").get("id").asText();
        for (int i = 0; i < 5; i++) {
            pay(payeeId, 100, "TEST " + i, key()).andExpect(status().isCreated());
        }
        pay(payeeId, 100, "TEST 6", key())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void health_is_public_and_metrics_need_their_own_scope() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").header(AUTHORIZATION, as("payments")))
                .andExpect(status().isForbidden());
    }

    @Test
    void every_response_carries_a_correlation_id() throws Exception {
        mvc.perform(get("/actuator/health").header(CorrelationIdFilter.HEADER, "test-correlation-1"))
                .andExpect(header().string(CorrelationIdFilter.HEADER, "test-correlation-1"));
        mvc.perform(get("/v1/payees"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(CorrelationIdFilter.HEADER));
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_openapi_spec_and_the_controllers_describe_the_same_operations() throws IOException {
        Set<String> served = new TreeSet<>();
        mappings.getHandlerMethods().keySet().forEach(info -> {
            for (String path : info.getPatternValues()) {
                if (path.startsWith("/v1/")) {
                    for (RequestMethod m : info.getMethodsCondition().getMethods()) {
                        served.add(m.name() + " " + path);
                    }
                }
            }
        });
        Map<String, Object> spec = new Yaml().load(Files.readString(Path.of("api/openapi.yaml")));
        Set<String> documented = new TreeSet<>();
        ((Map<String, Map<String, Object>>) spec.get("paths")).forEach((path, operations) ->
                operations.keySet().stream()
                        .filter(Set.of("get", "post", "put", "patch", "delete")::contains)
                        .forEach(m -> documented.add(m.toUpperCase(Locale.ROOT) + " " + path)));
        assertThat(served).isEqualTo(documented);
    }

    private String as(String scope) {
        return TestJwt.bearer(customer, scope);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private JsonNode registerPayee(String name, String sortCode, String account) throws Exception {
        String content = json.writeValueAsString(Map.of("name", name, "sortCode", sortCode, "accountNumber", account));
        return body(mvc.perform(post("/v1/payees").header(AUTHORIZATION, as("payments"))
                        .contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().isCreated()));
    }

    private ResultActions pay(String payeeId, long pence, String reference, String idempotencyKey) throws Exception {
        String content = json.writeValueAsString(Map.of("payeeId", payeeId, "amountPence", pence,
                "reference", reference));
        return mvc.perform(post("/v1/payments").header(AUTHORIZATION, as("payments"))
                .header(PaymentController.IDEMPOTENCY_KEY, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).content(content));
    }

    private ResultActions confirm(String paymentId, String warning) throws Exception {
        return mvc.perform(post("/v1/payments/" + paymentId + "/confirm").header(AUTHORIZATION, as("payments"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("acknowledgedWarning", warning))));
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
