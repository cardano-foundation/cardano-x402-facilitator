package org.cardanofoundation.x402.facilitator.config;

import org.cardanofoundation.x402.facilitator.chain.FacilitatorChainService;
import org.cardanofoundation.x402.facilitator.service.registry.CardanoNetworks;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared network readiness evaluation for the human and Actuator endpoints. */
@Component("chainReadiness")
public class ChainReadiness implements HealthIndicator {
    private final X402Properties props;
    private final Map<String, ChainBackendFactory.ChainBackend> backends;

    public ChainReadiness(X402Properties props, Map<String, ChainBackendFactory.ChainBackend> backends) {
        this.props = props;
        this.backends = backends;
    }

    public record Snapshot(boolean ready, List<Map<String, Object>> networks) {}

    public Snapshot snapshot() {
        boolean ready = true;
        List<Map<String, Object>> networks = new java.util.ArrayList<>();
        for (X402Properties.NetworkEntry entry : props.networks()) {
            String id = CardanoNetworks.normalize(entry.id());
            FacilitatorChainService chain = backends.containsKey(id) ? backends.get(id).chainService() : null;
            boolean healthy;
            String detail;
            try {
                var state = chain == null ? null : chain.health();
                healthy = state != null && state.healthy();
                detail = state == null ? "backend missing" : state.detail();
            } catch (RuntimeException e) {
                healthy = false;
                detail = "backend health check failed";
            }
            if (entry.isRequired() && !healthy) ready = false;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("required", entry.isRequired());
            item.put("healthy", healthy);
            item.put("detail", detail);
            networks.add(item);
        }
        return new Snapshot(ready, List.copyOf(networks));
    }

    @Override
    public Health health() {
        Snapshot snapshot = snapshot();
        return (snapshot.ready() ? Health.up() : Health.down())
                .withDetail("networks", snapshot.networks()).build();
    }
}
