package org.cardanofoundation.x402.facilitator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FacilitatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(FacilitatorApplication.class, args);
    }
}
