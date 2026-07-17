package org.cardanofoundation.x402.facilitator.config;

import org.cardanofoundation.x402.facilitator.chain.FacilitatorChainService;
import org.cardanofoundation.x402.facilitator.repository.SettlementRepository;
import org.cardanofoundation.x402.facilitator.service.registry.DefaultSchemeNetworkFacilitator;
import org.cardanofoundation.x402.facilitator.service.registry.X402FacilitatorRegistry;
import org.cardanofoundation.x402.facilitator.service.settlement.SettlementReconciler;
import org.cardanofoundation.x402.facilitator.service.settlement.SettlementService;
import org.cardanofoundation.x402.facilitator.service.verification.ExactCardanoScheme;
import org.cardanofoundation.x402.facilitator.service.verification.decoder.CardanoTransactionDecoder;
import org.cardanofoundation.x402.facilitator.service.verification.method.DefaultTransferVerifier;
import org.cardanofoundation.x402.facilitator.service.verification.method.TransferMethodVerifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(X402Properties.class)
public class FacilitatorConfig {

    @Bean
    public CardanoTransactionDecoder cardanoTransactionDecoder() {
        return new CardanoTransactionDecoder();
    }

    @Bean
    public DefaultTransferVerifier defaultTransferVerifier() {
        return new DefaultTransferVerifier();
    }

    @Bean
    public org.cardanofoundation.x402.facilitator.service.verification.method.masumi.MasumiTransferVerifier masumiTransferVerifier(
            X402Properties props, Map<String, ChainBackendFactory.ChainBackend> chainBackends) {
        // Per-network clocks (apply any slot-config override) for the M8 deadline.
        Map<String, org.cardanofoundation.x402.facilitator.chain.NetworkClock> clocks = new LinkedHashMap<>();
        chainBackends.forEach((network, backend) -> clocks.put(network, backend.networkClock()));
        // Per-network escrow script-hash allowlist (M1 / audit C1), normalized.
        Map<String, java.util.Set<String>> allowed = new LinkedHashMap<>();
        if (props.masumi() != null && props.masumi().allowedScriptHashes() != null) {
            props.masumi().allowedScriptHashes().forEach((network, hashes) -> {
                java.util.Set<String> set = new java.util.HashSet<>();
                if (hashes != null) hashes.forEach(h -> set.add(h.toLowerCase()));
                allowed.put(org.cardanofoundation.x402.facilitator.service.registry.CardanoNetworks.normalize(network), set);
            });
        }
        return new org.cardanofoundation.x402.facilitator.service.verification.method.masumi.MasumiTransferVerifier(
                clocks, allowed);
    }

    @Bean
    public org.cardanofoundation.x402.facilitator.service.verification.method.script.ScriptTransferVerifier scriptTransferVerifier(X402Properties props) {
        boolean v3DatumOptional = props.verification() != null
                && "v3-optional".equals(props.verification().scriptDatumPolicyOrDefault());
        return new org.cardanofoundation.x402.facilitator.service.verification.method.script.ScriptTransferVerifier(v3DatumOptional);
    }

    @Bean
    public ChainBackendFactory chainBackendFactory(
            org.springframework.beans.factory.ObjectProvider<YaciStoreBackendProvider> yaciStoreBackendProvider) {
        return new ChainBackendFactory(yaciStoreBackendProvider.getIfAvailable());
    }

    @Bean
    public Clock facilitatorClock() {
        return Clock.systemUTC();
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
    public org.cardanofoundation.x402.facilitator.service.settlement.SettlementGate settlementGate(
            Map<String, ChainBackendFactory.ChainBackend> chainBackends) {
        Map<String, org.cardanofoundation.x402.facilitator.chain.FacilitatorChainService> chains =
                new LinkedHashMap<>();
        chainBackends.forEach((network, backend) -> chains.put(network, backend.chainService()));
        return new org.cardanofoundation.x402.facilitator.service.settlement.SettlementGate(chains);
    }

    @Bean
    public SettlementReconciler settlementReconciler(X402Properties props,
                                                     Map<String, ChainBackendFactory.ChainBackend> chainBackends,
                                                     SettlementRepository settlementRepository,
                                                     DataSource dataSource,
                                                     Clock facilitatorClock) {
        Map<String, FacilitatorChainService> chains = new LinkedHashMap<>();
        chainBackends.forEach((network, backend) -> chains.put(network, backend.chainService()));
        boolean postgres = "postgresql".equals(FlywayConfig.vendor(dataSource));
        SettlementService.Config c = settlementConfig(props);
        return new SettlementReconciler(settlementRepository, chains, dataSource,
                c.confirmationDepth(), c.stabilityWindow(),
                props.settle() == null ? java.time.Duration.ofHours(24)
                        : props.settle().reconcileHorizonOrDefault(),
                facilitatorClock, postgres);
    }

    private static SettlementService.Config settlementConfig(X402Properties props) {
        X402Properties.Settle s = props.settle();
        X402Properties.DuplicateCache d = props.duplicateCache();
        return new SettlementService.Config(
                s == null ? java.time.Duration.ofSeconds(180) : s.confirmationTimeoutOrDefault(),
                s == null ? 1 : s.confirmationDepthOrDefault(),
                s != null && s.acceptMempoolOrDefault(),
                s != null && s.idempotentReplayOrDefault(),
                s == null ? java.time.Duration.ofMinutes(10) : s.stabilityWindowOrDefault(),
                d == null ? java.time.Duration.ofSeconds(120) : d.ttlOrDefault());
    }
}
