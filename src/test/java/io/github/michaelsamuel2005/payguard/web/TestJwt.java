package io.github.michaelsamuel2005.payguard.web;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Real RS256 tokens for the API tests, signed with a key pair made at start-up, so the tests exercise the actual
 * signature and expiry checks rather than a mocked principal.
 */
final class TestJwt {

    static final KeyPair KEYS = newKeyPair();

    private TestJwt() {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        JwtDecoder jwtDecoder() {
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
        }
    }

    static String bearer(String subject, String scope) {
        return "Bearer " + sign(subject, scope, Instant.now().plusSeconds(300), KEYS.getPrivate());
    }

    static String expired(String subject, String scope) {
        return "Bearer " + sign(subject, scope, Instant.now().minusSeconds(600), KEYS.getPrivate());
    }

    static String signedByAStranger(String subject, String scope) {
        return "Bearer " + sign(subject, scope, Instant.now().plusSeconds(300), newKeyPair().getPrivate());
    }

    private static String sign(String subject, String scope, Instant expires, PrivateKey key) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("scope", scope)
                .issueTime(Date.from(expires.minusSeconds(900)))
                .expirationTime(Date.from(expires))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims);
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
