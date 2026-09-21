package org.cardanofoundation.x402.facilitator.interop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.x402.facilitator.chain.*;
import org.cardanofoundation.x402.facilitator.config.JacksonConfig;
import org.cardanofoundation.x402.facilitator.controller.FacilitatorController;
import org.cardanofoundation.x402.facilitator.model.chain.*;
import org.cardanofoundation.x402.facilitator.repository.SettlementRepository;
import org.cardanofoundation.x402.facilitator.service.registry.*;
import org.cardanofoundation.x402.facilitator.service.settlement.*;
import org.cardanofoundation.x402.facilitator.service.verification.ExactCardanoScheme;
import org.cardanofoundation.x402.facilitator.service.verification.decoder.CardanoTransactionDecoder;
import org.cardanofoundation.x402.facilitator.service.verification.method.DefaultTransferVerifier;
import org.cardanofoundation.x402.facilitator.service.verification.method.masumi.MasumiTransferVerifier;
import org.cardanofoundation.x402.facilitator.service.verification.method.script.ScriptTransferVerifier;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.bind.annotation.*;

import java.math.BigInteger;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the actual Java HTTP controller and settlement journal against the published TS SDK.
 * All chain effects are controlled offline; the test-only control routes never enter the application jar. */
public class TypeScriptInterop {
    public static void main(String[] args) throws Exception {
        try (var context = new SpringApplicationBuilder(Server.class)
                .profiles("typescript-interop")
                .run("--spring.config.location=optional:classpath:interop-empty.yml", "--server.port=0",
                        "--server.address=127.0.0.1", "--spring.main.banner-mode=off")) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            int status = new ProcessBuilder("node", "interop/test.mjs", "http://127.0.0.1:" + port)
                    .inheritIO().start().waitFor();
            if (status != 0) throw new IllegalStateException("TypeScript interoperability checks failed: " + status);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("typescript-interop")
    @EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
    @Import({FacilitatorController.class, JacksonConfig.class, Controls.class})
    static class Server {
        @Bean Harness harness() throws Exception { return new Harness(); }
        @Bean CardanoTransactionDecoder decoder() { return new CardanoTransactionDecoder(); }
        @Bean DriverManagerDataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:interop;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean SettlementRepository repository(DriverManagerDataSource ds) {
            Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                    .schemas("facilitator").defaultSchema("facilitator").createSchemas(true).load().migrate();
            return new SettlementRepository(new NamedParameterJdbcTemplate(ds));
        }
        @Bean SettlementGate gate(Harness harness, SettlementRepository repo, CardanoTransactionDecoder decoder) {
            return new SettlementGate(harness.chains(), repo, decoder);
        }
        @Bean X402FacilitatorRegistry registry(Harness harness, SettlementRepository repo,
                                               CardanoTransactionDecoder decoder) {
            var registry = new X402FacilitatorRegistry(false);
            var clocks = new LinkedHashMap<String, NetworkClock>();
            harness.chains().keySet().forEach(n -> clocks.put(n, ShelleyNetworkClock.forNetwork(n, null)));
            var methods = List.of(new DefaultTransferVerifier(), new ScriptTransferVerifier("reference"),
                    new MasumiTransferVerifier(clocks, Map.of()));
            harness.chains().forEach((network, chain) -> {
                var scheme = new ExactCardanoScheme(chain,
                        () -> new ProtocolParams(BigInteger.valueOf(4310), 16384,
                                BigInteger.valueOf(44), BigInteger.valueOf(155381)),
                        decoder, new ArrayList<>(methods), 32768, clocks.get(network));
                var service = new SettlementService(repo, scheme, chain, decoder,
                        new SettlementService.Config(Duration.ofMillis(20), 1, false, false,
                                Duration.ofMinutes(10), Duration.ofSeconds(120)),
                        Clock.fixed(Instant.ofEpochMilli(harness.vectors.path("clockMillis").asLong()), ZoneOffset.UTC));
                registry.register(network, new DefaultSchemeNetworkFacilitator(scheme::verify, (p, r) -> {
                    harness.settleCalls.incrementAndGet();
                    var response = service.settle(p, r);
                    var state = harness.byHash.get(response.transaction());
                    if (state != null && state.includeAfterWait && "settlement_pending".equals(response.errorReason())) {
                        state.evidence = "included";
                        state.includeAfterWait = false;
                    }
                    return response;
                }));
            });
            return registry;
        }
    }

    static class Harness {
        final JsonNode vectors;
        final Map<String, PaymentState> byName = new LinkedHashMap<>();
        final Map<String, PaymentState> byHash = new ConcurrentHashMap<>();
        final AtomicInteger settleCalls = new AtomicInteger();
        final Map<String, FacilitatorChainService> networks = new LinkedHashMap<>();
        Harness() throws Exception {
            try (var in = TypeScriptInterop.class.getResourceAsStream("/upstream/payments.json")) {
                vectors = new ObjectMapper().readTree(in);
            }
            for (JsonNode fixture : vectors.path("payments")) {
                var state = new PaymentState(fixture);
                byName.put(fixture.path("name").asText(), state);
                byHash.put(fixture.path("txHash").asText(), state);
                String network = CardanoNetworks.normalize(fixture.path("requirements").path("network").asText());
                networks.computeIfAbsent(network, key -> new OfflineChain(this, key));
            }
        }
        Map<String, FacilitatorChainService> chains() { return networks; }
    }

    static class PaymentState {
        final JsonNode fixture;
        final AtomicInteger submissions = new AtomicInteger();
        volatile String evidence = "unknown";
        volatile String submit = "accepted";
        volatile int confirmations = 3;
        volatile boolean includeAfterWait;
        volatile boolean spent;
        PaymentState(JsonNode fixture) { this.fixture = fixture; }
    }

    static class OfflineChain implements FacilitatorChainService {
        final Harness harness;
        final String network;
        OfflineChain(Harness harness, String network) { this.harness = harness; this.network = network; }
        private List<PaymentState> states() {
            return harness.byName.values().stream().filter(s -> network.equals(CardanoNetworks.normalize(
                    s.fixture.path("requirements").path("network").asText()))).toList();
        }
        @Override public UtxoState getUtxoState(String hash, int index) {
            for (var state : states()) {
                JsonNode input = state.fixture.path("inputs").path(hash + "#" + index);
                if (input.isMissingNode()) continue;
                if (state.spent) return new UtxoState.Spent();
                var assets = new HashMap<String, BigInteger>();
                // Fixtures preserve all bigint quantities as decimal strings.
                input.path("assets").fields().forEachRemaining(e -> assets.put(e.getKey(), new BigInteger(e.getValue().asText())));
                return new UtxoState.Unspent(input.path("address").asText(), new BigInteger(input.path("coin").asText()), assets);
            }
            return new UtxoState.Spent();
        }
        @Override public long getCurrentSlot() { return states().getFirst().fixture.path("currentSlot").asLong(); }
        @Override public SubmissionResult submitTransaction(byte[] bytes) {
            String hash = new CardanoTransactionDecoder().decode(Base64.getEncoder().encodeToString(bytes)).txHashHex();
            var state = harness.byHash.get(hash);
            if (state == null) throw new AssertionError("Unexpected transaction submitted: " + hash);
            state.submissions.incrementAndGet();
            if (!state.submit.equals("notSubmitted")) state.spent = true;
            return switch (state.submit) {
                case "rejected" -> new SubmissionResult.Rejected("offline definitive rejection");
                case "unknown" -> new SubmissionResult.Unknown("offline transport timeout");
                case "notSubmitted" -> new SubmissionResult.NotSubmitted("offline local failure");
                default -> new SubmissionResult.Accepted(hash);
            };
        }
        @Override public InclusionResult checkInclusion(String hash) {
            var state = harness.byHash.get(hash);
            if (state == null) return new InclusionResult.NotSeen();
            return switch (state.evidence) {
                case "outage" -> throw new ChainLookupException("offline provider unavailable");
                case "included" -> new InclusionResult.Included(state.confirmations, getCurrentSlot(), "ab".repeat(32));
                case "mempool" -> new InclusionResult.Mempool();
                default -> new InclusionResult.NotSeen();
            };
        }
        @Override public InclusionResult awaitInclusion(String hash, int depth, Duration timeout) {
            return checkInclusion(hash);
        }
        @Override public BackendHealth health() {
            return states().stream().anyMatch(s -> s.evidence.equals("outage"))
                    ? BackendHealth.down("offline provider unavailable") : BackendHealth.ok();
        }
    }

    @RestController
    @Profile("typescript-interop")
    static class Controls {
        final Harness harness;
        Controls(Harness harness) { this.harness = harness; }
        @PostMapping("/__interop/control") Map<String, Object> control(@RequestBody Map<String, Object> args) {
            var state = Objects.requireNonNull(harness.byName.get(args.get("name")));
            if (args.containsKey("evidence")) state.evidence = (String) args.get("evidence");
            if (args.containsKey("submit")) state.submit = (String) args.get("submit");
            if (args.containsKey("confirmations")) state.confirmations = ((Number) args.get("confirmations")).intValue();
            if (args.containsKey("includeAfterWait")) state.includeAfterWait = (boolean) args.get("includeAfterWait");
            return Map.of("submissions", state.submissions.get(), "settleCalls", harness.settleCalls.get());
        }
    }
}
