package org.cardanofoundation.x402.facilitator.config;

import org.cardanofoundation.x402.facilitator.chain.FacilitatorChainService;
import org.cardanofoundation.x402.facilitator.chain.NetworkClock;
import org.cardanofoundation.x402.facilitator.repository.SettlementRepository;
import org.cardanofoundation.x402.facilitator.service.registry.CardanoNetworks;
import org.cardanofoundation.x402.facilitator.service.registry.DefaultSchemeNetworkFacilitator;
import org.cardanofoundation.x402.facilitator.service.registry.X402FacilitatorRegistry;
import org.cardanofoundation.x402.facilitator.service.settlement.SettlementGate;
import org.cardanofoundation.x402.facilitator.service.settlement.SettlementReconciler;
import org.cardanofoundation.x402.facilitator.service.settlement.SettlementService;
import org.cardanofoundation.x402.facilitator.service.verification.ExactCardanoScheme;
import org.cardanofoundation.x402.facilitator.service.verification.decoder.CardanoTransactionDecoder;
import org.cardanofoundation.x402.facilitator.service.verification.method.TransferMethodVerifier;
import org.cardanofoundation.x402.facilitator.service.verification.method.masumi.MasumiTransferVerifier;
import org.cardanofoundation.x402.facilitator.service.verification.method.script.ScriptTransferVerifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Configuration
@EnableConfigurationProperties(X402Properties.class)
public class FacilitatorConfig {

    @Bean
    public MasumiTransferVerifier masumiTransferVerifier(
            X402Properties props, Map<String, ChainBackendFactory.ChainBackend> chainBackends) {
        return new MasumiTransferVerifier(masumiClocks(chainBackends), allowedScriptHashes(props));
    }

    /** Per-network clocks (applying any slot-config override) for the Masumi M8 deadline. */
    private static Map<String, NetworkClock> masumiClocks(
            Map<String, ChainBackendFactory.ChainBackend> chainBackends) {
        Map<String, NetworkClock> clocks = new LinkedHashMap<>();
        chainBackends.forEach((network, backend) -> clocks.put(network, backend.networkClock()));
        return clocks;
    }

    /** Per-network escrow script-hash allowlist (rule M1), keys normalized and hashes lowercased. */
    private static Map<String, Set<String>> allowedScriptHashes(X402Properties props) {
        Map<String, Set<String>> allowed = new LinkedHashMap<>();
        if (props.masumi() != null && props.masumi().allowedScriptHashes() != null) {
            props.masumi().allowedScriptHashes().forEach((network, hashes) -> {
                Set<String> set = new HashSet<>();
                if (hashes != null) hashes.forEach(h -> set.add(h.toLowerCase()));
                allowed.put(CardanoNetworks.normalize(network), set);
            });
        }
        return allowed;
    }

    @Bean
    public ScriptTransferVerifier scriptTransferVerifier(X402Properties props) {
        String datumPolicy = props.verification() == null ? "reference" : props.verification().scriptDatumPolicyOrDefault();
        return new ScriptTransferVerifier(datumPolicy);
    }

    @Bean
    public Clock facilitatorClock() {
        return Clock.systemDefaultZone();
    }

    /** One chain backend per network entry; shared by the registry and the reconciler. */
    @Bean
    public Map<String, ChainBackendFactory.ChainBackend> chainBackends(X402Properties props,
                                                                       ChainBackendFactory factory) {
        Map<String, ChainBackendFactory.ChainBackend> backends = new LinkedHashMap<>();
        for (X402Properties.NetworkEntry entry : props.networks()) {
            backends.put(entry.id(), factory.build(entry, props));
        }
        return backends;
    }

    @Bean
    public X402FacilitatorRegistry registry(X402Properties props,
                                            Map<String, ChainBackendFactory.ChainBackend> chainBackends,
                                            CardanoTransactionDecoder decoder,
                                            List<TransferMethodVerifier> methodVerifiers,
                                            SettlementRepository settlementRepository,
                                            Clock facilitatorClock) {
        int maxTxBytes = props.verification() == null ? 32768 : props.verification().maxTxBytesOrDefault();
        SettlementService.Config settleConfig = settlementConfig(props);
        X402FacilitatorRegistry registry = new X402FacilitatorRegistry();
        for (X402Properties.NetworkEntry entry : props.networks()) {
            ChainBackendFactory.ChainBackend backend = chainBackends.get(entry.id());
            ExactCardanoScheme scheme = new ExactCardanoScheme(
                    backend.chainService(), backend.paramsProvider(), decoder, methodVerifiers, maxTxBytes);
            SettlementService settlement = new SettlementService(
                    settlementRepository, scheme, backend.chainService(), decoder,
                    settleConfig, facilitatorClock);
            registry.register(entry.id(), new DefaultSchemeNetworkFacilitator(
                    scheme::verify, settlement::settle));
        }
        return registry;
    }

    @Bean
    public SettlementGate settlementGate(Map<String, ChainBackendFactory.ChainBackend> chainBackends) {
        return new SettlementGate(chainServicesByNetwork(chainBackends));
    }

    @Bean
    public SettlementReconciler settlementReconciler(X402Properties props,
                                                     Map<String, ChainBackendFactory.ChainBackend> chainBackends,
                                                     SettlementRepository settlementRepository,
                                                     DataSource dataSource,
                                                     Clock facilitatorClock) {
        boolean postgres = "postgresql".equals(FlywayConfig.vendor(dataSource));
        SettlementService.Config c = settlementConfig(props);
        Duration reconcileHorizon = props.settle() == null ? Duration.ofHours(24)
                : props.settle().reconcileHorizonOrDefault();
        return new SettlementReconciler(settlementRepository, chainServicesByNetwork(chainBackends), dataSource,
                c.confirmationDepth(), c.stabilityWindow(), reconcileHorizon, facilitatorClock, postgres);
    }

    /** The chain service of each configured network, in configuration order. */
    private static Map<String, FacilitatorChainService> chainServicesByNetwork(
            Map<String, ChainBackendFactory.ChainBackend> chainBackends) {
        Map<String, FacilitatorChainService> chains = new LinkedHashMap<>();
        chainBackends.forEach((network, backend) -> chains.put(network, backend.chainService()));
        return chains;
    }

    private static SettlementService.Config settlementConfig(X402Properties props) {
        X402Properties.Settle s = props.settle();
        X402Properties.DuplicateCache d = props.duplicateCache();
        return new SettlementService.Config(
                s == null ? Duration.ofSeconds(180) : s.confirmationTimeoutOrDefault(),
                s == null ? 1 : s.confirmationDepthOrDefault(),
                s != null && s.acceptMempoolOrDefault(),
                s != null && s.idempotentReplayOrDefault(),
                s == null ? Duration.ofMinutes(10) : s.stabilityWindowOrDefault(),
                d == null ? Duration.ofSeconds(120) : d.ttlOrDefault());
    }
}
