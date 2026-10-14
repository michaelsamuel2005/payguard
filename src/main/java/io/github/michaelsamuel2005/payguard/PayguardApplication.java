package io.github.michaelsamuel2005.payguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot is the delivery layer only: the domain and services are plain Java, wired in WiringConfig. */
@SpringBootApplication
public class PayguardApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayguardApplication.class, args);
    }
}
