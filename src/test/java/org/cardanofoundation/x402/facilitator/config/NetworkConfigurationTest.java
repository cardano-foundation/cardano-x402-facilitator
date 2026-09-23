package org.cardanofoundation.x402.facilitator.config;

import org.cardanofoundation.x402.facilitator.chain.FacilitatorChainService;
import org.cardanofoundation.x402.facilitator.chain.ShelleyNetworkClock;
import org.cardanofoundation.x402.facilitator.model.chain.BackendHealth;
import org.cardanofoundation.x402.facilitator.model.chain.InclusionResult;
import org.cardanofoundation.x402.facilitator.model.entity.SettlementRecord;
import org.cardanofoundation.x402.facilitator.repository.SettlementRepository;
import org.cardanofoundation.x402.facilitator.service.verification.decoder.CardanoTransactionDecoder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetworkConfigurationTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC);
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"cip34:0-1,cardano:preprod", "cip34:0-2,cardano:preview", "cip34:1-764824073,cardano:mainnet"})
    void aliasConfiguredBackendSharesCanonicalHealthReconciliationAndClock(String alias, String canonical) {
        var chain = mock(FacilitatorChainService.class);
        when(chain.health()).thenReturn(BackendHealth.down("audit outage"));
        when(chain.checkInclusion(anyString())).thenReturn(new InclusionResult.NotSeen());
        var customClock = new ShelleyNetworkClock(100, 1_000_000, 2_000);
        var backend = new ChainBackendFactory.ChainBackend(chain, null,
                customClock);
        var factory = mock(ChainBackendFactory.class);
        when(factory.build(any(), any())).thenReturn(backend);
        var entry = new X402Properties.NetworkEntry(alias, true,
                new X402Properties.ChainConfig(new X402Properties.Blockfrost("https://example.invalid/", "")), null);
        var props = new X402Properties(List.of(entry), null, null, null, null, null);
        new StartupValidation(props).afterPropertiesSet(); // Alias is explicitly accepted configuration.
        var config = new FacilitatorConfig();
        var backends = config.chainBackends(props, factory);
        var repo = mock(SettlementRepository.class);
        var decoder = new CardanoTransactionDecoder();
        assertThat(backends).containsOnlyKeys(canonical);
        assertThat(config.settlementGate(backends, repo, decoder).isHealthy(canonical)).isFalse();
        verify(chain).health();
        var record = mock(SettlementRecord.class);
        when(record.network()).thenReturn(canonical);
        when(record.txHash()).thenReturn("ab".repeat(32));
        when(record.status()).thenReturn(SettlementRecord.Status.SUBMITTED);
        when(repo.dueForReconcile(any(), eq(200), isNull())).thenReturn(List.of(record));
        var ds = new DriverManagerDataSource("jdbc:h2:mem:audit_alias;DB_CLOSE_DELAY=-1", "sa", "");
        config.settlementReconciler(props, backends, repo, ds, clock).sweep();
        verify(chain).checkInclusion("ab".repeat(32));
        var verifier = config.masumiTransferVerifier(props, backends, mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.beans.factory.ObjectProvider.class));
        assertThat((Object) org.springframework.test.util.ReflectionTestUtils.invokeMethod(verifier, "clockFor", alias))
                .isSameAs(customClock);
    }

    @Test void canonicalDuplicateIsRejectedAtStartup() {
        var backend = new X402Properties.ChainConfig(new X402Properties.Blockfrost("https://example.invalid/", ""));
        for (var pair : List.of(List.of("cardano:preprod", "cip34:0-1"),
                List.of("cardano:mainnet", "cip34:1-764824073"), List.of("cardano:preview", "cip34:0-2"))) {
            var props = new X402Properties(pair.stream().map(id -> new X402Properties.NetworkEntry(id, true, backend, null)).toList(),
                    null, null, null, null, null);
            assertThatThrownBy(() -> new StartupValidation(props).afterPropertiesSet()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Duplicate");
            assertThatThrownBy(() -> new FacilitatorConfig().chainBackends(props, mock(ChainBackendFactory.class)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate");
        }
    }
}
