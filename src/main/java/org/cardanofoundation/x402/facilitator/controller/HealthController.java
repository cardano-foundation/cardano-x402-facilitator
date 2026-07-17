package org.cardanofoundation.x402.facilitator.controller;

import org.cardanofoundation.x402.facilitator.config.X402Properties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small human-facing summary; machine probes live under /actuator/health. */
@RestController
public class HealthController {

    private final X402Properties props;

    public HealthController(X402Properties props) {
        this.props = props;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("networks", props.networks().stream()
                .map(n -> Map.of("id", n.id(), "mode", n.chain().mode(), "required", n.isRequired()))
                .toList());
        return body;
    }
}
