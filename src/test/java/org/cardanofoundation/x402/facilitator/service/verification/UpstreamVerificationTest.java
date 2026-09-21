package org.cardanofoundation.x402.facilitator.service.verification;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import org.cardanofoundation.x402.facilitator.model.ErrorCodes;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.testutil.TestTx;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.math.BigInteger;
import static org.assertj.core.api.Assertions.assertThat;

class UpstreamVerificationTest {
    org.cardanofoundation.x402.facilitator.testutil.FakeChainService chain;
    ExactCardanoScheme scheme;
    @org.junit.jupiter.api.BeforeEach void setUp() {
        chain = new org.cardanofoundation.x402.facilitator.testutil.FakeChainService();
        chain.unspent.put(TestTx.NONCE, TestTx.PAYER_ADDRESS);
        scheme = new ExactCardanoScheme(chain, chain,
                new org.cardanofoundation.x402.facilitator.service.verification.decoder.CardanoTransactionDecoder(),
                List.of(new org.cardanofoundation.x402.facilitator.service.verification.method.DefaultTransferVerifier()),32768,
                org.cardanofoundation.x402.facilitator.chain.ShelleyNetworkClock.forNetwork("cardano:preprod",null));
    }
    PaymentRequirements requirements(String network,String payTo,String amount,String asset) {
        return new PaymentRequirements("exact",network,asset,amount,payTo,600,Map.of("assetTransferMethod","default"));
    }
    org.cardanofoundation.x402.facilitator.model.protocol.PaymentPayload payload(String tx,String nonce,PaymentRequirements req) {
        return new org.cardanofoundation.x402.facilitator.model.protocol.PaymentPayload(2,null,req,
                Map.of("transaction",tx,"nonce",nonce),null);
    }
    org.cardanofoundation.x402.facilitator.model.protocol.VerifyResponse verifyDefault() {
        var req = requirements("cardano:preprod",TestTx.PAY_TO,"2000000","lovelace");
        return scheme.verify(payload(TestTx.buildBase64(TestTx.Spec.defaults()),TestTx.NONCE,req),req);
    }
    @Test void explicitNullConfirmationPolicyIsMalformed() {
        Map<String,Object> extra = new HashMap<>(); extra.put("confirmationPolicy", null);
        assertThat(CardanoPolicies.l1Confirmations(extra)).isNull();
    }
    @Test void overflowingConfirmationCountIsMalformed() {
        assertThat(CardanoPolicies.l1Confirmations(Map.of("confirmationPolicy",
                Map.of("l1Confirmations", 4294967297L)))).isNull();
    }
    @Test void nonCanonicalAmountsAreRejected() {
        for (String amount : List.of("+2000000", "02000000")) {
            var req = requirements("cardano:preprod", TestTx.PAY_TO, amount, "lovelace");
            assertThat(scheme.verify(payload(TestTx.buildBase64(TestTx.Spec.defaults()), TestTx.NONCE, req),req)
                    .invalidReason()).isEqualTo(ErrorCodes.REQUIREMENTS_INVALID);
        }
    }
    @Test void uppercaseAssetIsRejected() {
        var req = requirements("cardano:preprod", TestTx.PAY_TO, "2000000", "LOVELACE");
        assertThat(scheme.verify(payload(TestTx.buildBase64(TestTx.Spec.defaults()),TestTx.NONCE,req),req)
                .invalidReason()).isEqualTo(ErrorCodes.REQUIREMENTS_INVALID);
    }
    @Test void nonCanonicalBase64IsRejected() {
        var req = requirements("cardano:preprod", TestTx.PAY_TO, "2000000", "lovelace");
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        // Unpadded strings are accepted by java.util.Base64, but are not canonical wire data.
        for (long ttl : new long[]{1, 256, 65536, 1000000}) {
            if (tx.endsWith("=")) break;
            tx = TestTx.buildBase64(TestTx.Spec.defaults().withTtl(ttl));
        }
        assertThat(tx).endsWith("=");
        assertThat(scheme.verify(payload(tx.replace("=", ""),TestTx.NONCE,req),req)
                .invalidReason()).isEqualTo(ErrorCodes.DECODE_FAILED);
    }
    @Test void duplicateInputsFailPhase1() {
        var req = requirements("cardano:preprod", TestTx.PAY_TO, "2000000", "lovelace");
        String tx = TestTx.buildBase64(TestTx.Spec.defaults().withExtraInputs(List.of(new TransactionInput(TestTx.NONCE_TX_HASH,0))));
        assertThat(scheme.verify(payload(tx,TestTx.NONCE,req),req).invalidReason())
                .isEqualTo("invalid_exact_cardano_payload_phase1_invalid");
    }
    @Test void invalidLedgerFlagFailsEvenWithValidSignature() throws Exception {
        var req = requirements("cardano:preprod", TestTx.PAY_TO, "2000000", "lovelace");
        var raw = (co.nstant.in.cbor.model.Array) co.nstant.in.cbor.CborDecoder.decode(
                Base64.getDecoder().decode(TestTx.buildBase64(TestTx.Spec.defaults()))).getFirst();
        raw.getDataItems().set(2, co.nstant.in.cbor.model.SimpleValue.FALSE);
        var bytes = new java.io.ByteArrayOutputStream(); new co.nstant.in.cbor.CborEncoder(bytes).encode(raw);
        assertThat(scheme.verify(payload(Base64.getEncoder().encodeToString(bytes.toByteArray()),TestTx.NONCE,req),req)
                .invalidReason()).isEqualTo("invalid_exact_cardano_payload_phase2_invalid");
    }

    @Test void inputValuesAreRequired() {
        chain.overrides.put(TestTx.NONCE, new org.cardanofoundation.x402.facilitator.model.chain.UtxoState.Unspent(TestTx.PAYER_ADDRESS));
        assertThat(verifyDefault().invalidReason()).isEqualTo(ErrorCodes.INPUT_VALUE_UNAVAILABLE);
    }
    @Test void lovelaceMustBeConserved() {
        chain.overrides.put(TestTx.NONCE, new org.cardanofoundation.x402.facilitator.model.chain.UtxoState.Unspent(
                TestTx.PAYER_ADDRESS, BigInteger.valueOf(9_999_999), Map.of()));
        assertThat(verifyDefault().invalidReason()).isEqualTo(ErrorCodes.VALUE_NOT_CONSERVED);
    }
    @Test void nativeTokensMustBeConserved() {
        chain.overrides.put(TestTx.NONCE, new org.cardanofoundation.x402.facilitator.model.chain.UtxoState.Unspent(
                TestTx.PAYER_ADDRESS, BigInteger.valueOf(10_000_000), Map.of("aa".repeat(28)+".01", BigInteger.ONE)));
        assertThat(verifyDefault().invalidReason()).isEqualTo(ErrorCodes.VALUE_NOT_CONSERVED);
    }
    @Test void insufficientFeeFailsEvenWhenBalanced() throws Exception {
        var req = requirements("cardano:preprod", TestTx.PAY_TO, "2000000", "lovelace");
        var tx = com.bloxbean.cardano.client.transaction.spec.Transaction.deserialize(
                Base64.getDecoder().decode(TestTx.buildBase64(TestTx.Spec.defaults())));
        tx.getBody().setFee(BigInteger.ONE);
        tx.getBody().getOutputs().get(1).getValue().setCoin(BigInteger.valueOf(7_999_999));
        tx.setWitnessSet(new com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet());
        tx = com.bloxbean.cardano.client.transaction.TransactionSigner.INSTANCE.sign(tx, TestTx.PAYER_KEY);
        assertThat(scheme.verify(payload(Base64.getEncoder().encodeToString(tx.serialize()),TestTx.NONCE,req),req)
                .invalidReason()).isEqualTo(ErrorCodes.FEE_BELOW_MINIMUM);
    }
    @Test void excessiveInputsFailBeforeProviderWork() {
        var req = requirements("cardano:preprod", TestTx.PAY_TO, "2000000", "lovelace");
        var extras = new ArrayList<TransactionInput>();
        for (int i=1; i<=256; i++) extras.add(new TransactionInput(TestTx.NONCE_TX_HASH,i));
        String tx = TestTx.buildBase64(TestTx.Spec.defaults().withExtraInputs(extras));
        assertThat(scheme.verify(payload(tx,TestTx.NONCE,req),req).invalidReason()).isEqualTo(ErrorCodes.PHASE1_INVALID);
    }
    @Test void mintNeedsCompleteValidator() throws Exception {
        var req = requirements("cardano:preprod", TestTx.PAY_TO, "2000000", "lovelace");
        var tx = com.bloxbean.cardano.client.transaction.spec.Transaction.deserialize(
                Base64.getDecoder().decode(TestTx.buildBase64(TestTx.Spec.defaults())));
        tx.getBody().setMint(List.of(new com.bloxbean.cardano.client.transaction.spec.MultiAsset("aa".repeat(28),
                List.of(new com.bloxbean.cardano.client.transaction.spec.Asset("asset",BigInteger.ONE)))));
        tx.setWitnessSet(new com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet());
        tx = com.bloxbean.cardano.client.transaction.TransactionSigner.INSTANCE.sign(tx, TestTx.PAYER_KEY);
        assertThat(scheme.verify(payload(Base64.getEncoder().encodeToString(tx.serialize()),TestTx.NONCE,req),req)
                .invalidReason()).isEqualTo(ErrorCodes.PHASE1_INVALID);
    }

    @Test void broadcastRetryRechecksRecipientAmountAndAuthorizationWithSpentInputs() {
        chain.unspent.clear(); chain.throwOnLookup = true; chain.currentSlot = 2_000_000;
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        var req = requirements("cardano:preprod",TestTx.PAY_TO,"2000000","lovelace");
        assertThat(scheme.verifyBroadcast(payload(tx,TestTx.NONCE,req),req,TestTx.PAYER_ADDRESS).isValid()).isTrue();
        var wrongRecipient = requirements("cardano:preprod",TestTx.THIRD_PARTY_ADDRESS,"2000000","lovelace");
        assertThat(scheme.verifyBroadcast(payload(tx,TestTx.NONCE,wrongRecipient),wrongRecipient,TestTx.PAYER_ADDRESS)
                .invalidReason()).isEqualTo(ErrorCodes.RECIPIENT_MISMATCH);
        var wrongAmount = requirements("cardano:preprod",TestTx.PAY_TO,"2000001","lovelace");
        assertThat(scheme.verifyBroadcast(payload(tx,TestTx.NONCE,wrongAmount),wrongAmount,TestTx.PAYER_ADDRESS)
                .invalidReason()).isEqualTo(ErrorCodes.AMOUNT_INSUFFICIENT);
        assertThat(scheme.verifyBroadcast(payload(tx,TestTx.NONCE,req),req,TestTx.THIRD_PARTY_ADDRESS)
                .invalidReason()).isEqualTo(ErrorCodes.PAYER_NOT_WITNESS);
    }
    @Test void broadcastRetryStillChecksTransferMethod() {
        chain.unspent.clear();
        var req = new PaymentRequirements("exact","cardano:preprod","lovelace","2000000",TestTx.PAY_TO,600,
                Map.of("assetTransferMethod","unsupported"));
        assertThat(scheme.verifyBroadcast(payload(TestTx.buildBase64(TestTx.Spec.defaults()),TestTx.NONCE,req),req,TestTx.PAYER_ADDRESS)
                .invalidReason()).isEqualTo(ErrorCodes.UNSUPPORTED_SCHEME);
    }
    @Test void completeValidatorAlsoRunsOnPlainPaymentsAndExceptionsFailClosed() {
        scheme = new ExactCardanoScheme(chain,chain,new org.cardanofoundation.x402.facilitator.service.verification.decoder.CardanoTransactionDecoder(),
                List.of(new org.cardanofoundation.x402.facilitator.service.verification.method.DefaultTransferVerifier()),32768,
                org.cardanofoundation.x402.facilitator.chain.ShelleyNetworkClock.forNetwork("cardano:preprod",null),
                (tx,inputs,params,network) -> { throw new IllegalArgumentException("ledger rejected"); });
        assertThat(verifyDefault().invalidReason()).isEqualTo(ErrorCodes.PHASE1_INVALID);
    }
    @Test void nativeAssetConservationUsesLiteralInputAndOutputQuantities() throws Exception {
        String policy = "aa".repeat(28), asset = policy+".01";
        chain.overrides.put(TestTx.NONCE,new org.cardanofoundation.x402.facilitator.model.chain.UtxoState.Unspent(
                TestTx.PAYER_ADDRESS,BigInteger.valueOf(10_000_000),Map.of(asset,BigInteger.valueOf(5))));
        var req = requirements("cardano:preprod",TestTx.PAY_TO,"4",asset);
        for (long[] output : List.of(new long[]{9_800_000,5},new long[]{9_800_001,5},new long[]{9_800_000,4})) {
            var tx = com.bloxbean.cardano.client.transaction.spec.Transaction.deserialize(
                    Base64.getDecoder().decode(TestTx.buildBase64(TestTx.Spec.defaults())));
            var payment = tx.getBody().getOutputs().getFirst();
            payment.getValue().setCoin(BigInteger.valueOf(output[0]));
            payment.getValue().setMultiAssets(List.of(new com.bloxbean.cardano.client.transaction.spec.MultiAsset(policy,
                    List.of(new com.bloxbean.cardano.client.transaction.spec.Asset("0x01",BigInteger.valueOf(output[1]))))));
            tx.getBody().setOutputs(List.of(payment));
            tx.setWitnessSet(new com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet());
            tx = com.bloxbean.cardano.client.transaction.TransactionSigner.INSTANCE.sign(tx,TestTx.PAYER_KEY);
            var result = scheme.verify(payload(Base64.getEncoder().encodeToString(tx.serialize()),TestTx.NONCE,req),req);
            if (output[0] == 9_800_000 && output[1] == 5) assertThat(result.isValid()).as(result.invalidReason()).isTrue();
            else assertThat(result.invalidReason()).isEqualTo(ErrorCodes.VALUE_NOT_CONSERVED);
        }
    }

    @Test void feeBoundaryMatchesIndependent500ByteVector() {
        var real = new org.cardanofoundation.x402.facilitator.service.verification.decoder.CardanoTransactionDecoder()
                .decode(TestTx.buildBase64(TestTx.Spec.defaults()));
        for (long fee : new long[]{177380,177381}) {
            var decoder = new org.cardanofoundation.x402.facilitator.service.verification.decoder.CardanoTransactionDecoder() {
                @Override public org.cardanofoundation.x402.facilitator.model.verification.DecodedTransaction decode(String ignored) {
                    return new org.cardanofoundation.x402.facilitator.model.verification.DecodedTransaction(
                            real.txHashHex(),real.inputs(),real.outputs(),real.ttlSlot(),real.validityStartSlot(),real.networkId(),
                            real.vkeyWitnessCount(),real.scriptWitnessCount(),real.signaturesValid(),real.verifiedWitnessKeyHashes(),
                            500,BigInteger.valueOf(fee),true,real.bodyKeys(),real.requiredSignerKeyHashes(),real.rawBytes());
                }
            };
            chain.overrides.put(TestTx.NONCE,new org.cardanofoundation.x402.facilitator.model.chain.UtxoState.Unspent(
                    TestTx.PAYER_ADDRESS,BigInteger.valueOf(9_800_000+fee),Map.of()));
            scheme = new ExactCardanoScheme(chain,chain,decoder,
                    List.of(new org.cardanofoundation.x402.facilitator.service.verification.method.DefaultTransferVerifier()),32768,
                    org.cardanofoundation.x402.facilitator.chain.ShelleyNetworkClock.forNetwork("cardano:preprod",null));
            if (fee == 177380) assertThat(verifyDefault().invalidReason()).isEqualTo(ErrorCodes.FEE_BELOW_MINIMUM);
            else assertThat(verifyDefault().isValid()).isTrue();
        }
    }
}
