package org.cardanofoundation.x402.facilitator.controller;

import lombok.RequiredArgsConstructor;
import org.cardanofoundation.x402.facilitator.config.X402Properties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small human-facing summary; machine probes live under /actuator/health. */
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final X402Properties props;

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("networks", props.networks().stream()
                .map(n -> Map.of("id", n.id(), "required", n.isRequired()))
                .toList());
        return body;
    }
}
