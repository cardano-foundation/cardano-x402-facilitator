package org.cardanofoundation.x402.facilitator.controller;

import lombok.RequiredArgsConstructor;
import org.cardanofoundation.x402.facilitator.config.ChainReadiness;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small human-facing summary; machine probes live under /actuator/health. */
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final ChainReadiness readiness;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        ChainReadiness.Snapshot snapshot = readiness.snapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", snapshot.ready() ? "ok" : "unavailable");
        body.put("networks", snapshot.networks());
        return ResponseEntity.status(snapshot.ready() ? 200 : 503).body(body);
    }
}
