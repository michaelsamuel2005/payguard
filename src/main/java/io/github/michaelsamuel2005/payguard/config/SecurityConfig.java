package io.github.michaelsamuel2005.payguard.config;

import io.github.michaelsamuel2005.payguard.application.PaymentRateLimiter;
import io.github.michaelsamuel2005.payguard.web.RateLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * A stateless OAuth 2.0 resource server. Every API call needs a signed JWT; the {@code scope} claim decides what it
 * may do: {@code payments} for a customer's own payees and payments, {@code reviews} for the review queue,
 * {@code metrics} for Prometheus. Anything not listed is denied.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, PaymentRateLimiter limiter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable) // no cookies or sessions: bearer tokens only
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/error")
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
