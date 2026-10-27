package org.cardanofoundation.x402.facilitator.e2e.kit;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import lombok.RequiredArgsConstructor;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds real, signed default-method payments with QuickTx against the devnet,
 * plus the CBOR-level mutations the rejection tests need. The facilitator never
 * signs: everything here is what a client would send.
 */
@RequiredArgsConstructor
public final class PaymentFactory {

    /** Validity window of a fresh payment; within the default maxTimeoutSeconds. */
    public static final long DEFAULT_TTL_SLOTS = 300;
    public static final int DEFAULT_MAX_TIMEOUT_SECONDS = 600;

    private final DevnetKit kit;

    public Spec from(Wallet payer) {
        return new Spec(payer);
    }

    /**
     * Re-encodes the payment after {@code mutation} and RE-SIGNS it with the
     * payer, so the signatures stay valid. Everything else is the caller's job:
     * the fee is NOT recomputed (a size-growing mutation can trip
     * fee_below_minimum first), {@code auxiliaryDataHash} is NOT re-derived when
     * the mutation touches auxiliary data, and {@code payload.nonce} still names
     * the base's first input. Every rejection case should prove its base with a
     * no-op {@code mutateAndResign(base, tx -> {})} that verifies valid.
     */
    public Payment mutateAndResign(Payment base, Consumer<Transaction> mutation) {
        return mutate(base, mutation, List.of(base.payer().account()));
    }

    /**
     * Re-encodes after {@code mutation}, re-signing with exactly {@code signers}
     * (none: the payment goes out unsigned).
     */
    public Payment mutate(Payment base, Consumer<Transaction> mutation, List<Account> signers) {
        Transaction tx = DevnetKit.call(() -> Transaction.deserialize(base.txBytes()));
        mutation.accept(tx);
        if (tx.getWitnessSet() == null) tx.setWitnessSet(new TransactionWitnessSet());
        // Only the vkey witnesses go: native-script witnesses must survive a re-sign.
        tx.getWitnessSet().setVkeyWitnesses(new ArrayList<>());
        for (Account signer : signers) {
            tx = signer.sign(tx);
        }
        return withTxBytes(base, DevnetKit.serialize(tx));
    }

    /**
     * Re-encodes after {@code mutation} but KEEPS the original witnesses — the
     * signatures no longer cover the body.
     */
    public Payment mutateKeepingWitnesses(Payment base, Consumer<Transaction> mutation) {
        Transaction tx = DevnetKit.call(() -> Transaction.deserialize(base.txBytes()));
        mutation.accept(tx);
        return withTxBytes(base, DevnetKit.serialize(tx));
    }

    /** The same request carrying other transaction bytes (nonce unchanged). */
    public static Payment withTxBytes(Payment base, byte[] txBytes) {
        String hash = TransactionUtil.getTxHash(txBytes).toLowerCase();
        return new Payment(base.payer(), txBytes, hash, base.requirements(), base.payload())
                .withPayload("transaction", Base64.getEncoder().encodeToString(txBytes));
    }

    /** A payment under construction. Defaults: 2 ADA, lovelace, TTL 300 slots, default method. */
    public final class Spec {
        private final Wallet payer;
        private String payTo;
        private String asset = "lovelace";
        private BigInteger amount = BigInteger.valueOf(2_000_000);
        private BigInteger sent;
        private long ttlSlots = DEFAULT_TTL_SLOTS;
        private Metadata metadata;
        private PlutusData inlineDatum;

        private Spec(Wallet payer) {
            this.payer = payer;
        }

        public Spec payTo(String address) {
            this.payTo = address;
            return this;
        }

        public Spec payTo(Wallet receiver) {
            return payTo(receiver.address());
        }

        /** Lovelace amount, required and sent. */
        public Spec lovelace(long lovelace) {
            this.asset = "lovelace";
            this.amount = BigInteger.valueOf(lovelace);
            return this;
        }

        /** Native token amount, by canonical id {@code <policyHex>.<assetNameHex>}. */
        public Spec token(String canonicalAsset, long quantity) {
            this.asset = canonicalAsset;
            this.amount = BigInteger.valueOf(quantity);
            return this;
        }

        /** Sends more than the requirements ask for (overpay). */
        public Spec send(long quantity) {
            this.sent = BigInteger.valueOf(quantity);
            return this;
        }

        public Spec ttlSlots(long slotsFromTip) {
            this.ttlSlots = slotsFromTip;
            return this;
        }

        /** Attaches auxiliary data; QuickTx sets the matching body hash. */
        /**
         * Puts an INLINE datum on the paid output (script and Masumi escrow locks).
         * The paid output carries the token or the lovelace amount, plus whatever
         * min-UTxO QuickTx adds for the datum's bytes.
         */
        public Spec inlineDatum(PlutusData datum) {
            this.inlineDatum = datum;
            return this;
        }

        public Spec metadata(Metadata metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Builds and signs against the wallet's LIVE UTxO set. Two payments built
         * back to back from one wallet, before the first settles, pick the same
         * input (and so the same nonce); a test that needs independent unsettled
         * payments uses one wallet per payment.
         */
        public Payment build() {
            if (payTo == null) throw new IllegalStateException("payTo is required");
            BigInteger quantity = sent == null ? amount : sent;
            Amount value = "lovelace".equals(asset)
                    ? Amount.lovelace(quantity)
                    : Amount.asset(asset.replace(".", ""), quantity);
            Tx tx = (inlineDatum == null
                    ? new Tx().payToAddress(payTo, value)
                    : new Tx().payToContract(payTo, value, inlineDatum))
                    .from(payer.address());
            if (metadata != null) tx.attachMetadata(metadata);

            Transaction signed = new QuickTxBuilder(kit.getBackend()).compose(tx)
                    .validTo(kit.tipSlot() + ttlSlots)
                    .withSigner(SignerProviders.signerFrom(payer.account()))
                    .buildAndSign();
            byte[] bytes = DevnetKit.serialize(signed);
            TransactionInput first = signed.getBody().getInputs().get(0);

            Map<String, Object> requirements = new LinkedHashMap<>();
            requirements.put("scheme", "exact");
            requirements.put("network", DevnetKit.NETWORK);
            requirements.put("asset", asset);
            requirements.put("amount", amount.toString());
            requirements.put("payTo", payTo);
            requirements.put("maxTimeoutSeconds", DEFAULT_MAX_TIMEOUT_SECONDS);
            requirements.put("extra", Map.of("assetTransferMethod", "default"));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("transaction", Base64.getEncoder().encodeToString(bytes));
            payload.put("nonce", first.getTransactionId().toLowerCase() + "#" + first.getIndex());

            return new Payment(payer, bytes, TransactionUtil.getTxHash(bytes).toLowerCase(), requirements, payload);
        }
    }
}
