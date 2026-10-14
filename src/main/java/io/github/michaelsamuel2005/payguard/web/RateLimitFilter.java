package io.github.michaelsamuel2005.payguard.web;

import io.github.michaelsamuel2005.payguard.application.PaymentRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limits how fast one customer can start payments: 429 with Retry-After once their bucket is empty. It runs inside
 * the security chain, after the token is read, so it limits by authenticated subject. Not a Spring bean on purpose:
 * a bean filter would also be registered on its own, outside the security chain.
 */
public final class RateLimitFilter extends OncePerRequestFilter {

    private final PaymentRateLimiter limiter;

    public RateLimitFilter(PaymentRateLimiter limiter) {
        this.limiter = Objects.requireNonNull(limiter, "limiter");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && "/v1/payments".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token && !limiter.tryAcquire(token.getName())) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(Math.max(1, limiter.retryAfterSeconds(token.getName()))));
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                    + "\"detail\":\"too many payment requests; retry after the time in Retry-After\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
