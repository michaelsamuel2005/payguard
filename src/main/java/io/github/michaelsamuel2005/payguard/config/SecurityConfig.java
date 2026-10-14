package io.github.michaelsamuel2005.payguard.config;

import io.github.michaelsamuel2005.payguard.application.PaymentRateLimiter;
import io.github.michaelsamuel2005.payguard.web.RateLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

/**
 * A stateless OAuth 2.0 resource server. Every API call needs a signed JWT; the {@code scope} claim decides what it
 * may do: {@code payments} for a customer's own payees and payments, {@code reviews} for the review queue,
 * {@code metrics} for Prometheus. Anything not listed is denied.
 *
 * <p>The one exception is the demo web page: its HTML, CSS and JavaScript (src/main/resources/static) are public,
 * because a page must load before it can hold a token. They carry no data. Everything the page shows comes from the
 * API, with the token. See docs/adr/0007-a-static-page-served-by-the-api.md.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /**
     * What the page may load and do: its own scripts, styles and requests, nothing inline, nothing from elsewhere,
     * no framing. The page is written to need no more than this (it has no inline script or style), so a
     * cross-site-scripting bug could not load an attacker's script or send data to another site. The browser tests
     * (ui-tests) run against this exact text and fail on any violation.
     */
    public static final String CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=(), payment=()";

    @Bean
    SecurityFilterChain api(HttpSecurity http, PaymentRateLimiter limiter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable) // no cookies or sessions: bearer tokens only
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", PERMISSIONS_POLICY)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/error")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/about.html", "/accessibility.html",
                                "/assets/**")
                        .permitAll()
                        .requestMatchers("/actuator/prometheus").hasAuthority("SCOPE_metrics")
                        .requestMatchers("/v1/reviews", "/v1/reviews/**").hasAuthority("SCOPE_reviews")
                        .requestMatchers("/v1/**").hasAuthority("SCOPE_payments")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .addFilterAfter(new RateLimitFilter(limiter), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
