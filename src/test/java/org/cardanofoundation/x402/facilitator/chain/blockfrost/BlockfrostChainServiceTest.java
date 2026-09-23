package org.cardanofoundation.x402.facilitator.chain.blockfrost;

import com.bloxbean.cardano.client.api.model.*;
import com.bloxbean.cardano.client.backend.api.BackendService;
import org.cardanofoundation.x402.facilitator.model.chain.*;
import org.cardanofoundation.x402.facilitator.model.chain.ProtocolParams;
import org.cardanofoundation.x402.facilitator.testutil.TestTx;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.cardanofoundation.x402.facilitator.chain.ShelleyNetworkClock;
import org.cardanofoundation.x402.facilitator.chain.ChainLookupException;
import org.cardanofoundation.x402.facilitator.model.ErrorCodes;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentPayload;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.service.verification.ExactCardanoScheme;
import org.cardanofoundation.x402.facilitator.service.verification.decoder.CardanoTransactionDecoder;
import org.cardanofoundation.x402.facilitator.service.verification.method.DefaultTransferVerifier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlockfrostChainServiceTest {
    final BackendService backend = mock(BackendService.class, RETURNS_DEEP_STUBS);
    final BlockfrostChainService chain = new BlockfrostChainService(backend, Duration.ofMillis(1), null,null);
    final byte[] tx = Base64.getDecoder().decode(TestTx.buildBase64(TestTx.Spec.defaults()));
    @Test void pathologicalAddressScanHasAHardProviderCallBudget() throws Exception {
        Utxo output = Utxo.builder().txHash(TestTx.NONCE_TX_HASH).outputIndex(0)
                .address(TestTx.PAYER_ADDRESS).build();
        Utxo other = Utxo.builder().txHash("ff".repeat(32)).outputIndex(0).build();
        when(backend.getUtxoService().getTxOutput(TestTx.NONCE_TX_HASH, 0))
                .thenReturn(Result.success("").withValue(output));
        when(backend.getUtxoService().getUtxos(eq(TestTx.PAYER_ADDRESS), eq(100), anyInt()))
                .thenReturn(Result.success("").withValue(Collections.nCopies(100, other)));
        assertThat(chain.getUtxoState(TestTx.NONCE_TX_HASH, 0)).isInstanceOf(UtxoState.Unknown.class);
        verify(backend.getUtxoService(), atMost(63)).getUtxos(anyString(), anyInt(), anyInt());
    }

    @Test void sessionReusesAddressPagesAndNonceAcrossInputs() throws Exception {
        List<Utxo> outputs = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            Utxo output = Utxo.builder().txHash(TestTx.NONCE_TX_HASH).outputIndex(i)
                    .address(TestTx.PAYER_ADDRESS).build();
            outputs.add(output);
            when(backend.getUtxoService().getTxOutput(TestTx.NONCE_TX_HASH, i))
                    .thenReturn(Result.success("").withValue(output));
        }
        when(backend.getUtxoService().getUtxos(TestTx.PAYER_ADDRESS, 100, 1))
                .thenReturn(Result.success("").withValue(outputs.subList(0, 100)));
        when(backend.getUtxoService().getUtxos(TestTx.PAYER_ADDRESS, 100, 2))
                .thenReturn(Result.success("").withValue(outputs.subList(100, 150)));
        var lookup = chain.openUtxoLookup();
        for (int i : List.of(0, 0, 1, 120, 40, 149))
            assertThat(lookup.getUtxoState(TestTx.NONCE_TX_HASH, i)).isInstanceOf(UtxoState.Unspent.class);
        verify(backend.getUtxoService()).getTxOutput(TestTx.NONCE_TX_HASH, 0);
        verify(backend.getUtxoService(), times(2)).getUtxos(anyString(), anyInt(), anyInt());
        // A new request must fetch a fresh live set.
        assertThat(chain.openUtxoLookup().getUtxoState(TestTx.NONCE_TX_HASH, 0)).isInstanceOf(UtxoState.Unspent.class);
        verify(backend.getUtxoService(), times(2)).getUtxos(TestTx.PAYER_ADDRESS, 100, 1);
    }

    @Test void totalBudgetCoversAllInputsAndFailsClosedWithoutFurtherCalls() throws Exception {
        List<Utxo> outputs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Utxo output = Utxo.builder().txHash(TestTx.NONCE_TX_HASH).outputIndex(i)
                    .address(TestTx.PAYER_ADDRESS).build();
            outputs.add(output);
            when(backend.getUtxoService().getTxOutput(TestTx.NONCE_TX_HASH, i))
                    .thenReturn(Result.success("").withValue(output));
        }
        when(backend.getUtxoService().getUtxos(TestTx.PAYER_ADDRESS, 100, 1))
                .thenReturn(Result.success("").withValue(outputs));
        var lookup = chain.openUtxoLookup();
        for (int i = 0; i < 63; i++)
            assertThat(lookup.getUtxoState(TestTx.NONCE_TX_HASH, i)).isInstanceOf(UtxoState.Unspent.class);
        for (int i = 63; i < 100; i++)
            assertThat(lookup.getUtxoState(TestTx.NONCE_TX_HASH, i)).isInstanceOf(UtxoState.Unknown.class);
        verify(backend.getUtxoService(), times(63)).getTxOutput(anyString(), anyInt());
        verify(backend.getUtxoService(), times(1)).getUtxos(anyString(), anyInt(), anyInt());
    }

    @Test void blockedProviderReturnsUnknownAtRequestDeadline() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        when(backend.getUtxoService().getTxOutput(TestTx.NONCE_TX_HASH, 0)).thenAnswer(invocation -> {
            // Deliberately ignore cancellation, as an SDK transport may do.
            while (release.getCount() > 0) {
                try { release.await(); } catch (InterruptedException ignored) { }
            }
            return Result.error("stopped").code(503);
        });
        try {
            var lookup = chain.openUtxoLookup(new org.cardanofoundation.x402.facilitator.chain.LookupBudget(
                    64, Duration.ofMillis(100)));
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                assertThat(lookup.getUtxoState(TestTx.NONCE_TX_HASH, 0)).isInstanceOf(UtxoState.Unknown.class);
                assertThat(lookup.getUtxoState(TestTx.NONCE_TX_HASH, 1)).isInstanceOf(UtxoState.Unknown.class);
            });
            verify(backend.getUtxoService(), atMostOnce()).getTxOutput(anyString(), anyInt());
            verify(backend.getUtxoService(), never()).getUtxos(anyString(), anyInt(), anyInt());
        } finally { release.countDown(); }
    }

    @Test void currentSlotAdvancesBetweenBlocksUsingTheConfiguredNetworkClock() throws Exception {
        var latest = new com.bloxbean.cardano.client.backend.model.Block();
        latest.setSlot(9_999_970L); // No block has been produced for thirty slots.
        when(backend.getBlockService().getLatestBlock()).thenReturn(Result.success("").withValue(latest));
        for (String network : List.of("cardano:mainnet", "cardano:preprod", "cardano:preview", "cip34:0-1")) {
            var slots = ShelleyNetworkClock.forNetwork(network, null);
            var now = Clock.fixed(slots.slotToTime(10_000_000).plusMillis(999), ZoneOffset.UTC);
            var configured = new BlockfrostChainService(backend, Duration.ofMillis(1), null, null, slots, now);
            assertThat(configured.getCurrentSlot()).as(network).isEqualTo(10_000_000);
            var later = new BlockfrostChainService(backend, Duration.ofMillis(1), null, null, slots,
                    Clock.offset(now, Duration.ofSeconds(17)));
            assertThat(later.getCurrentSlot()).as(network).isEqualTo(10_000_017);
        }
    }

    @Test void customSlotLengthIsRespected() throws Exception {
        var latest = new com.bloxbean.cardano.client.backend.model.Block(); latest.setSlot(100L);
        when(backend.getBlockService().getLatestBlock()).thenReturn(Result.success("").withValue(latest));
        var slots = new ShelleyNetworkClock(100, 1_000_000, 2_000);
        var now = Clock.fixed(Instant.ofEpochMilli(1_011_999), ZoneOffset.UTC);
        assertThat(new BlockfrostChainService(backend, Duration.ofMillis(1), null, null, slots, now).getCurrentSlot())
                .isEqualTo(105);
    }

    @Test void healthyClockDoesNotHideAProviderOutage() throws Exception {
        when(backend.getBlockService().getLatestBlock()).thenReturn(Result.error("unavailable").code(503));
        var slots = ShelleyNetworkClock.forNetwork("cardano:preprod", null);
        var configured = new BlockfrostChainService(backend, Duration.ofMillis(1), null, null, slots,
                Clock.fixed(slots.slotToTime(1_000_000), ZoneOffset.UTC));
        assertThat(configured.getCurrentSlot()).isEqualTo(1_000_000);
        assertThat(configured.health().healthy()).isFalse();
    }

    @Test void legacyConstructorCannotMistakeBlockTipForCurrentSlot() {
        assertThatThrownBy(chain::getCurrentSlot).isInstanceOf(ChainLookupException.class)
                .hasMessageContaining("network clock");
    }

    @Test void demoExpiryAtQuotedTimeoutPassesBetweenBlocksButLongerExpiryStillFails() throws Exception {
        var slots = ShelleyNetworkClock.forNetwork("cardano:preprod", null);
        var now = Clock.fixed(slots.slotToTime(999_400L), ZoneOffset.UTC);
        var latest = new com.bloxbean.cardano.client.backend.model.Block(); latest.setSlot(999_370L);
        when(backend.getBlockService().getLatestBlock()).thenReturn(Result.success("").withValue(latest));
        var configured = new BlockfrostChainService(backend, Duration.ofMillis(1), null, null, slots, now);
        Utxo input = Utxo.builder().txHash(TestTx.NONCE_TX_HASH).outputIndex(0).address(TestTx.PAYER_ADDRESS)
                .amount(List.of(new Amount("lovelace", BigInteger.valueOf(10_000_000)))).build();
        when(backend.getUtxoService().getTxOutput(TestTx.NONCE_TX_HASH, 0)).thenReturn(Result.success("").withValue(input));
        when(backend.getUtxoService().getUtxos(TestTx.PAYER_ADDRESS, 100, 1)).thenReturn(Result.success("").withValue(List.of(input)));
        var scheme = new ExactCardanoScheme(configured, () -> new ProtocolParams(BigInteger.valueOf(4310),
                16384, BigInteger.valueOf(44), BigInteger.valueOf(155381)), new CardanoTransactionDecoder(),
                List.of(new DefaultTransferVerifier()), 32768, slots);
        var requirements = new PaymentRequirements("exact", "cardano:preprod", "lovelace", "2000000",
                TestTx.PAY_TO, 600, Map.of("assetTransferMethod", "default"));
        var atLimit = new PaymentPayload(2, null, requirements,
                Map.of("transaction", TestTx.buildBase64(TestTx.Spec.defaults()), "nonce", TestTx.NONCE), null);
        assertThat(scheme.verify(atLimit, requirements).isValid()).isTrue(); // TTL 1,000,000 = now + 600.
        var tooFar = new PaymentPayload(2, null, requirements,
                Map.of("transaction", TestTx.buildBase64(TestTx.Spec.defaults().withTtl(1_000_001L)), "nonce", TestTx.NONCE), null);
        assertThat(scheme.verify(tooFar, requirements).invalidReason()).isEqualTo(ErrorCodes.TTL_TOO_FAR);
        var expired = new PaymentPayload(2, null, requirements,
                Map.of("transaction", TestTx.buildBase64(TestTx.Spec.defaults().withTtl(999_400L)), "nonce", TestTx.NONCE), null);
        assertThat(scheme.verify(expired, requirements).invalidReason()).isEqualTo(ErrorCodes.TTL_EXPIRED);
    }

    @Test void healthyProviderIsStillProbedWithoutAValidityClock() throws Exception {
        var latest = new com.bloxbean.cardano.client.backend.model.Block(); latest.setSlot(999_370L);
        when(backend.getBlockService().getLatestBlock()).thenReturn(Result.success("").withValue(latest));
        assertThat(chain.health().healthy()).isTrue();
        verify(backend.getBlockService()).getLatestBlock();
    }
    @Test void wrongHashIsUnknownNotAcceptance() throws Exception {
        when(backend.getTransactionService().submitTransaction(tx)).thenReturn(Result.success("").withValue("aa".repeat(32)));
        assertThat(chain.submitTransaction(tx)).isInstanceOf(SubmissionResult.Unknown.class);
    }
    @Test void serverFailuresAreUncertain() throws Exception {
        when(backend.getTransactionService().submitTransaction(tx)).thenReturn(Result.error("upstream unavailable").code(503));
        assertThat(chain.submitTransaction(tx)).isInstanceOf(SubmissionResult.Unknown.class);
    }
    @Test void authenticationFailuresAreNotLedgerRejections() throws Exception {
        when(backend.getTransactionService().submitTransaction(tx)).thenReturn(Result.error("project quota exceeded").code(403));
        assertThat(chain.submitTransaction(tx)).isInstanceOf(SubmissionResult.Unknown.class);
    }
    @Test void ledgerValidationFailureIsDefinitive() throws Exception {
        when(backend.getTransactionService().submitTransaction(tx)).thenReturn(Result.error("{\"error\":\"Bad Request\",\"message\":\"ValueNotConservedUTxO\"}").code(400));
        assertThat(chain.submitTransaction(tx)).isInstanceOf(SubmissionResult.Rejected.class);
    }
    @Test void snapshotContainsAuthenticatedCoinAndCanonicalTokenUnits() throws Exception {
        String token = "ab".repeat(28)+"01";
        Utxo output = Utxo.builder().txHash(TestTx.NONCE_TX_HASH).outputIndex(0).address(TestTx.PAYER_ADDRESS)
                .amount(List.of(new Amount("lovelace",BigInteger.valueOf(10_000_000)),new Amount(token,BigInteger.valueOf(5)))).build();
        when(backend.getUtxoService().getTxOutput(TestTx.NONCE_TX_HASH,0)).thenReturn(Result.success("").withValue(output));
        when(backend.getUtxoService().getUtxos(TestTx.PAYER_ADDRESS,100,1)).thenReturn(Result.success("").withValue(List.of(output)));
        var state = (UtxoState.Unspent) chain.getUtxoState(TestTx.NONCE_TX_HASH,0);
        assertThat(state.coin()).isEqualTo(BigInteger.valueOf(10_000_000));
        assertThat(state.assets()).containsEntry("ab".repeat(28)+".01",BigInteger.valueOf(5));
        assertThatThrownBy(() -> state.assets().put("bad",BigInteger.ONE)).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void protocolFeeCoefficientsAreRead() throws Exception {
        var pp = new com.bloxbean.cardano.client.api.model.ProtocolParams();
        pp.setCoinsPerUtxoSize("4310"); pp.setMaxTxSize(16384); pp.setMinFeeA(44); pp.setMinFeeB(155381);
        when(backend.getEpochService().getProtocolParameters()).thenReturn(Result.success("").withValue(pp));
        var actual = new BlockfrostProtocolParamsProvider(backend).current();
        assertThat(actual.minFeeCoefficient()).isEqualTo(BigInteger.valueOf(44));
        assertThat(actual.minFeeConstant()).isEqualTo(BigInteger.valueOf(155381));
    }

    @Test void expiredProtocolParametersAreNeverServedAfterProviderFailure() throws Exception {
        var pp = new com.bloxbean.cardano.client.api.model.ProtocolParams();
        pp.setCoinsPerUtxoSize("4310"); pp.setMaxTxSize(16384); pp.setMinFeeA(44); pp.setMinFeeB(155381);
        when(backend.getEpochService().getProtocolParameters()).thenReturn(Result.success("").withValue(pp));
        var provider = new BlockfrostProtocolParamsProvider(backend);
        provider.current();
        var fetchedAt = BlockfrostProtocolParamsProvider.class.getDeclaredField("fetchedAtMillis");
        fetchedAt.setAccessible(true); fetchedAt.setLong(provider,0);
        when(backend.getEpochService().getProtocolParameters()).thenReturn(Result.error("down").code(503));
        assertThatThrownBy(provider::current).isInstanceOf(org.cardanofoundation.x402.facilitator.chain.ChainLookupException.class);
    }

    @Test void phase2InvalidInclusionNeverAuthenticatesPaymentOutputs() throws Exception {
        var content = new com.bloxbean.cardano.client.backend.model.TransactionContent();
        content.setHash(TestTx.NONCE_TX_HASH); content.setBlock("bb".repeat(32));
        content.setSlot(100L); content.setBlockHeight(10L); content.setValidContract(false);
        var latest = new com.bloxbean.cardano.client.backend.model.Block(); latest.setHeight(11L);
        when(backend.getTransactionService().getTransaction(TestTx.NONCE_TX_HASH)).thenReturn(Result.success("").withValue(content));
        when(backend.getBlockService().getLatestBlock()).thenReturn(Result.success("").withValue(latest));
        assertThatThrownBy(() -> chain.checkInclusion(TestTx.NONCE_TX_HASH))
                .isInstanceOf(org.cardanofoundation.x402.facilitator.chain.ChainLookupException.class);
    }
}
