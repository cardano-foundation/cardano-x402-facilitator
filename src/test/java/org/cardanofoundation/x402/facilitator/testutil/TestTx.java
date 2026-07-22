package org.cardanofoundation.x402.facilitator.testutil;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.address.CredentialType;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.SecretKey;
import com.bloxbean.cardano.client.crypto.VerificationKey;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.TransactionSigner;
import com.bloxbean.cardano.client.transaction.spec.*;
import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Deterministic signed-transaction fixtures for decoder/scheme tests. */
public final class TestTx {
    // Fixed 32-byte Ed25519 seed => stable payer key/address across runs.
    public static final SecretKey PAYER_KEY =
            new SecretKey("5820" + "11".repeat(32));
    public static final VerificationKey PAYER_VKEY;
    public static final String PAYER_ADDRESS;
    public static final String PAY_TO; // the "server" address fixtures pay to
    public static final String SELLER_ADDRESS; // fixed key-cred masumi seller address
    public static final String NONCE_TX_HASH = "ab".repeat(32);
    public static final String NONCE = NONCE_TX_HASH + "#0";

    // DUMMY masumi purchase identifiers (see docs/superpowers/plans/2026-07-06-masumi-extension.md
    // Global Constraints) -- fabricated stand-ins for the real values a Masumi Payment
    // Service purchase would supply. Shared by MasumiSpec.defaults() and
    // MasumiTransferVerifierTest so the fixture and the test's `extra` map stay in sync.
    public static final String MASUMI_REFERENCE_KEY = "a1b2c3d4";
    public static final String MASUMI_REFERENCE_SIGNATURE =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"; // 32 bytes
    public static final String MASUMI_SELLER_NONCE = "8877665544332211";
    public static final String MASUMI_IDENTIFIER_FROM_PURCHASER = "1122334455667788";
    public static final String MASUMI_AGENT_IDENTIFIER = "deadbeefdeadbeefdeadbeefdeadbeef";
    public static final String MASUMI_INPUT_HASH = "";
    public static final BigInteger MASUMI_COLLATERAL_RETURN_LOVELACE = BigInteger.ZERO;
    public static final BigInteger MASUMI_PAY_BY_TIME = new BigInteger("2000000000000");
    public static final BigInteger MASUMI_SUBMIT_RESULT_TIME = new BigInteger("2000000600000");
    public static final BigInteger MASUMI_UNLOCK_TIME = new BigInteger("2000001200000");
    public static final BigInteger MASUMI_EXTERNAL_DISPUTE_UNLOCK_TIME = new BigInteger("2000001800000");
    // 5 tADA: comfortably above min-UTxO-with-datum (mirrors Task M2's masumi route price).
    public static final BigInteger MASUMI_AMOUNT = BigInteger.valueOf(5_000_000L);

    // `script` assetTransferMethod conformance fixture: the shared MINIMAL_PLUTUS_V3
    // script (raw flat, single-CBOR-wrapped), its ledger script hash, and its
    // enterprise (nid 0) script address — known-good vectors the S1 derivation must match.
    public static final String SCRIPT_CODE_V3 = "4d01000033222220051200120011";
    public static final String SCRIPT_HASH_V3 = "4fff649fb4372ec3c408b6f0468d74e4d319904cde27fd3f00910a52";
    public static final String SCRIPT_ADDR_V3 =
            "addr_test1wp8l7eylksmjas7ypzm0q35dwnjdxxvsfn0z0lflqzgs55stpd682";
    // Same script with the single parameter `bigint 42` applied.
    public static final String SCRIPT_HASH_V3_INT42 = "7bfdc59e675e288dc869395143d2ed2d227bd23f0663449c847f6fcc";
    // The same code hashed as PlutusV1 / PlutusV2 (different language tag => different
    // hash/address); used to exercise the S3 per-version datum policy.
    public static final String SCRIPT_ADDR_V1 =
            "addr_test1wpnlxv2xv9a9ucvnvzqakwepzl9ltx7jzgm53av2e9ncv4sysemm8";
    public static final String SCRIPT_ADDR_V2 =
            "addr_test1wpunlryvl7aqsxe22erzlsseej87v5kk5vutvtrmzdy8dect48z0w";

    static {
        try {
            PAYER_VKEY = KeyGenUtil.getPublicKeyFromPrivateKey(PAYER_KEY);
            PAYER_ADDRESS = AddressProvider.getEntAddress(
                    Credential.fromKey(KeyGenUtil.getKeyHash(PAYER_VKEY)), Networks.testnet()).toBech32();
            SecretKey serverKey = new SecretKey("5820" + "22".repeat(32));
            PAY_TO = AddressProvider.getEntAddress(
                    Credential.fromKey(KeyGenUtil.getKeyHash(KeyGenUtil.getPublicKeyFromPrivateKey(serverKey))),
                    Networks.testnet()).toBech32();
            SecretKey sellerKey = new SecretKey("5820" + "33".repeat(32));
            SELLER_ADDRESS = AddressProvider.getEntAddress(
                    Credential.fromKey(KeyGenUtil.getKeyHash(KeyGenUtil.getPublicKeyFromPrivateKey(sellerKey))),
                    Networks.testnet()).toBech32();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    public record Spec(String payTo, BigInteger amount, Long ttl, Long validityStart,
                       NetworkId networkId, boolean sign, List<TransactionInput> extraInputs) {
        public static Spec defaults() {
            return new Spec(PAY_TO, BigInteger.valueOf(2_000_000L), 1_000_000L, null, null, true, List.of());
        }
        public Spec withPayTo(String v) { return new Spec(v, amount, ttl, validityStart, networkId, sign, extraInputs); }
        public Spec withAmount(BigInteger v) { return new Spec(payTo, v, ttl, validityStart, networkId, sign, extraInputs); }
        public Spec withTtl(Long v) { return new Spec(payTo, amount, v, validityStart, networkId, sign, extraInputs); }
        public Spec withValidityStart(Long v) { return new Spec(payTo, amount, ttl, v, networkId, sign, extraInputs); }
        public Spec withNetworkId(NetworkId v) { return new Spec(payTo, amount, ttl, validityStart, v, sign, extraInputs); }
        public Spec unsigned() { return new Spec(payTo, amount, ttl, validityStart, networkId, false, extraInputs); }
        public Spec withExtraInputs(List<TransactionInput> v) { return new Spec(payTo, amount, ttl, validityStart, networkId, sign, v); }
    }

    /** Builds a signed (or unsigned) tx: input = NONCE utxo (+extras), output0 = payment, output1 = change. */
    public static String buildBase64(Spec spec) {
        try {
            List<TransactionInput> inputs = new ArrayList<>();
            inputs.add(new TransactionInput(NONCE_TX_HASH, 0));
            inputs.addAll(spec.extraInputs());
            TransactionOutput payment = TransactionOutput.builder()
                    .address(spec.payTo())
                    .value(Value.builder().coin(spec.amount()).build()).build();
            TransactionOutput change = TransactionOutput.builder()
                    .address(PAYER_ADDRESS)
                    .value(Value.builder().coin(BigInteger.valueOf(7_000_000L)).build()).build();
            TransactionBody.TransactionBodyBuilder body = TransactionBody.builder()
                    .inputs(inputs).outputs(List.of(payment, change))
                    .fee(BigInteger.valueOf(170_000L));
            if (spec.ttl() != null) body.ttl(spec.ttl());
            if (spec.validityStart() != null) body.validityStartInterval(spec.validityStart());
            if (spec.networkId() != null) body.networkId(spec.networkId());
            Transaction tx = Transaction.builder().body(body.build())
                    .witnessSet(new TransactionWitnessSet()).build();
            Transaction result = spec.sign() ? TransactionSigner.INSTANCE.sign(tx, PAYER_KEY) : tx;
            return Base64.getEncoder().encodeToString(result.serialize());
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** A signed tx whose signature bytes were corrupted after signing. */
    public static String buildBase64WithBadSignature() {
        byte[] raw = Base64.getDecoder().decode(buildBase64(Spec.defaults()));
        // Signatures sit near the end of [body, witnessSet, ...]; flip a byte inside the
        // witness area. Adjust index if the assert below fails to find a signature change.
        raw[raw.length - 40] ^= 0x01;
        return Base64.getEncoder().encodeToString(raw);
    }

    /** cclib can't emit ttl=0, so rewrite the body map: key 3 -> 0. Unsigned is fine
     *  for decoder assertions (ttl extraction doesn't depend on witnesses). */
    public static String buildBase64TtlZero() {
        try {
            byte[] raw = Base64.getDecoder().decode(buildBase64(Spec.defaults().withTtl(1L).unsigned()));
            co.nstant.in.cbor.model.Array tx = (co.nstant.in.cbor.model.Array)
                    co.nstant.in.cbor.CborDecoder.decode(raw).get(0);
            co.nstant.in.cbor.model.Map body = (co.nstant.in.cbor.model.Map) tx.getDataItems().get(0);
            body.put(new co.nstant.in.cbor.model.UnsignedInteger(3),
                     new co.nstant.in.cbor.model.UnsignedInteger(0));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            new co.nstant.in.cbor.CborEncoder(out).encode(tx);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /**
     * Knobs for building the 19-field masumi lock datum (see
     * ../x402/typescript/packages/mechanisms/cardano/src/exact/masumi/datum.ts). Each
     * `with*` mutator corrupts exactly one thing so tests can target a single failure.
     */
    public record MasumiSpec(
            BigInteger amount,
            String buyerAddress,
            boolean buyerIsScript,
            String sellerAddress,
            boolean sellerIsScript,
            long rootAlt,
            int fieldCount,
            String referenceKeyHex,
            String referenceSignatureHex,
            String sellerNonceHex,
            String buyerNonceHex, // identifierFromPurchaser
            String agentIdentifierHex,
            BigInteger collateralReturnLovelace,
            String inputHashHex,
            String resultHashHex,
            BigInteger payByTime,
            BigInteger submitResultTime,
            BigInteger unlockTime,
            BigInteger externalDisputeUnlockTime,
            long stateAlt,
            boolean cooldownCorrupt) { // f16 seller_cooldown_time as a non-integer (Constr) when true

        public static MasumiSpec defaults() {
            return new MasumiSpec(
                    MASUMI_AMOUNT,
                    PAYER_ADDRESS, false,
                    SELLER_ADDRESS, false,
                    0L, 19,
                    MASUMI_REFERENCE_KEY,
                    MASUMI_REFERENCE_SIGNATURE,
                    MASUMI_SELLER_NONCE,
                    MASUMI_IDENTIFIER_FROM_PURCHASER,
                    MASUMI_AGENT_IDENTIFIER,
                    MASUMI_COLLATERAL_RETURN_LOVELACE,
                    MASUMI_INPUT_HASH,
                    "", // result_hash: empty at a fresh lock
                    MASUMI_PAY_BY_TIME,
                    MASUMI_SUBMIT_RESULT_TIME,
                    MASUMI_UNLOCK_TIME,
                    MASUMI_EXTERNAL_DISPUTE_UNLOCK_TIME,
                    0L, // FundsLocked
                    false); // well-typed cooldowns
        }

        public MasumiSpec withAmount(BigInteger v) {
            return new MasumiSpec(v, buyerAddress, buyerIsScript, sellerAddress, sellerIsScript, rootAlt,
                    fieldCount, referenceKeyHex, referenceSignatureHex, sellerNonceHex, buyerNonceHex,
                    agentIdentifierHex, collateralReturnLovelace, inputHashHex, resultHashHex, payByTime,
                    submitResultTime, unlockTime, externalDisputeUnlockTime, stateAlt, cooldownCorrupt);
        }
        public MasumiSpec withCollateralReturnLovelace(BigInteger v) {
            return new MasumiSpec(amount, buyerAddress, buyerIsScript, sellerAddress, sellerIsScript, rootAlt,
                    fieldCount, referenceKeyHex, referenceSignatureHex, sellerNonceHex, buyerNonceHex,
                    agentIdentifierHex, v, inputHashHex, resultHashHex, payByTime,
                    submitResultTime, unlockTime, externalDisputeUnlockTime, stateAlt, cooldownCorrupt);
        }
        public MasumiSpec withBuyerAddress(String v) {
            return new MasumiSpec(amount, v, buyerIsScript, sellerAddress, sellerIsScript, rootAlt,
                    fieldCount, referenceKeyHex, referenceSignatureHex, sellerNonceHex, buyerNonceHex,
                    agentIdentifierHex, collateralReturnLovelace, inputHashHex, resultHashHex, payByTime,
                    submitResultTime, unlockTime, externalDisputeUnlockTime, stateAlt, cooldownCorrupt);
        }
        public MasumiSpec withBuyerIsScript(boolean v) {
            return new MasumiSpec(amount, buyerAddress, v, sellerAddress, sellerIsScript, rootAlt,
                    fieldCount, referenceKeyHex, referenceSignatureHex, sellerNonceHex, buyerNonceHex,
                    agentIdentifierHex, collateralReturnLovelace, inputHashHex, resultHashHex, payByTime,
                    submitResultTime, unlockTime, externalDisputeUnlockTime, stateAlt, cooldownCorrupt);
        }
        public MasumiSpec withSellerAddress(String v) {
            return new MasumiSpec(amount, buyerAddress, buyerIsScript, v, sellerIsScript, rootAlt,
                    fieldCount, referenceKeyHex, referenceSignatureHex, sellerNonceHex, buyerNonceHex,
                    agentIdentifierHex, collateralReturnLovelace, inputHashHex, resultHashHex, payByTime,
                    submitResultTime, unlockTime, externalDisputeUnlockTime, stateAlt, cooldownCorrupt);
        }
        public MasumiSpec withSellerIsScript(boolean v) {
            return new MasumiSpec(amount, buyerAddress, buyerIsScript, sellerAddress, v, rootAlt,
                    fieldCount, referenceKeyHex, referenceSignatureHex, sellerNonceHex, buyerNonceHex,
                    agentIdentifierHex, collateralReturnLovelace, inputHashHex, resultHashHex, payByTime,
                    submitResultTime, unlockTime, externalDisputeUnlockTime, stateAlt, cooldownCorrupt);
        }
        public MasumiSpec withRootAlt(long v) {
            return new MasumiSpec(amount, buyerAddress, buyerIsScript, sellerAddress, sellerIsScript, v,
                    fieldCount, referenceKeyHex, referenceSignatureHex, sellerNonceHex, buyerNonceHex,
                    agentIdentifierHex, collateralReturnLovelace, inputHashHex, resultHashHex, payByTime,
                    submitResultTime, unlockTime, externalDisputeUnlockTime, stateAlt, cooldownCorrupt);
        }
        public MasumiSpec withFieldCount(int v) {
            return new MasumiSpec(amount, buyerAddress, buyerIsScript, sellerAddress, sellerIsScript, rootAlt,
                    v, referenceKeyHex, referenceSignatureHex, sellerNonceHex, buyerNonceHex,
                    agentIdentifierHex, collateralReturnLovelace, inputHashHex, resultHashHex, payByTime,
                    submitResultTime, unlockTime, externalDisputeUnlockTime, stateAlt, cooldownCorrupt);
        }
        public MasumiSpec withReferenceKeyHex(String v) {
            return new MasumiSpec(amount, buyerAddress, buyerIsScript, sellerAddress, sellerIsScript, rootAlt,
                    fieldCount, v, referenceSignatureHex, sellerNonceHex, buyerNonceHex,
                    agentIdentifierHex, collateralReturnLovelace, inputHashHex, resultHashHex, payByTime,
                    submitResultTime, unlockTime, externalDisputeUnlockTime, stateAlt, cooldownCorrupt);
        }
        public MasumiSpec withReferenceSignatureHex(String v) {
            return new MasumiSpec(amount, buyerAddress, buyerIsScript, sellerAddress, sellerIsScript, rootAlt,
                    fieldCount, referenceKeyHex, v, sellerNonceHex, buyerNonceHex,
                    agentIdentifierHex, collateralReturnLovelace, inputHashHex, resultHashHex, payByTime,
                    submitResultTime, unlockTime, externalDisputeUnlockTime, stateAlt, cooldownCorrupt);
        }
        public MasumiSpec withResultHashHex(String v) {
            return new MasumiSpec(amount, buyerAddress, buyerIsScript, sellerAddress, sellerIsScript, rootAlt,
                    fieldCount, referenceKeyHex, referenceSignatureHex, sellerNonceHex, buyerNonceHex,
                    agentIdentifierHex, collateralReturnLovelace, inputHashHex, v, payByTime,
                    submitResultTime, unlockTime, externalDisputeUnlockTime, stateAlt, cooldownCorrupt);
        }
        public MasumiSpec withPayByTime(BigInteger v) {
            return new MasumiSpec(amount, buyerAddress, buyerIsScript, sellerAddress, sellerIsScript, rootAlt,
                    fieldCount, referenceKeyHex, referenceSignatureHex, sellerNonceHex, buyerNonceHex,
                    agentIdentifierHex, collateralReturnLovelace, inputHashHex, resultHashHex, v,
                    submitResultTime, unlockTime, externalDisputeUnlockTime, stateAlt, cooldownCorrupt);
        }
        public MasumiSpec withUnlockTime(BigInteger v) {
            return new MasumiSpec(amount, buyerAddress, buyerIsScript, sellerAddress, sellerIsScript, rootAlt,
                    fieldCount, referenceKeyHex, referenceSignatureHex, sellerNonceHex, buyerNonceHex,
                    agentIdentifierHex, collateralReturnLovelace, inputHashHex, resultHashHex, payByTime,
                    submitResultTime, v, externalDisputeUnlockTime, stateAlt, cooldownCorrupt);
        }
        public MasumiSpec withStateAlt(long v) {
            return new MasumiSpec(amount, buyerAddress, buyerIsScript, sellerAddress, sellerIsScript, rootAlt,
                    fieldCount, referenceKeyHex, referenceSignatureHex, sellerNonceHex, buyerNonceHex,
                    agentIdentifierHex, collateralReturnLovelace, inputHashHex, resultHashHex, payByTime,
                    submitResultTime, unlockTime, externalDisputeUnlockTime, v, cooldownCorrupt);
        }
        /** Corrupts f16 seller_cooldown_time to a non-integer (empty Constr) -- TS reads it as asInt. */
        public MasumiSpec withCooldownCorrupt(boolean v) {
            return new MasumiSpec(amount, buyerAddress, buyerIsScript, sellerAddress, sellerIsScript, rootAlt,
                    fieldCount, referenceKeyHex, referenceSignatureHex, sellerNonceHex, buyerNonceHex,
                    agentIdentifierHex, collateralReturnLovelace, inputHashHex, resultHashHex, payByTime,
                    submitResultTime, unlockTime, externalDisputeUnlockTime, stateAlt, v);
        }
    }

    /**
     * Builds a signed preprod tx whose output-0 pays {@code payTo} the spec's lovelace
     * amount WITH an inline datum = the 19-field masumi lock datum, and output-1 is the
     * payer's change. Mirrors {@link #buildBase64} but attaches the masumi datum.
     */
    public static String buildMasumiLockBase64(String payTo, MasumiSpec spec) {
        return buildMasumiLockBase64(payTo, spec, 1_000_000L, false, null);
    }

    /**
     * Advanced masumi lock builder for the canonical rules the simple form can't reach:
     * {@code ttlSlot} (null omits the TTL — M8 deadline "no upper bound"), a spoofing
     * reference script on the escrow output (M2 reference-script rejection), and a
     * datum {@code seller_cooldown_time} override (M3 non-zero cooldown).
     */
    public static String buildMasumiLockBase64(String payTo, MasumiSpec spec, Long ttlSlot,
                                               boolean withReferenceScript, BigInteger sellerCooldownOverride) {
        try {
            List<TransactionInput> inputs = new ArrayList<>();
            inputs.add(new TransactionInput(NONCE_TX_HASH, 0));
            PlutusData datum = buildMasumiDatum(spec);
            if (sellerCooldownOverride != null && datum instanceof ConstrPlutusData c
                    && c.getData().getPlutusDataList().size() > 16) {
                c.getData().getPlutusDataList().set(16, BigIntPlutusData.of(sellerCooldownOverride));
            }
            TransactionOutput payment = TransactionOutput.builder()
                    .address(payTo)
                    .value(Value.builder().coin(spec.amount()).build()).build();
            payment.setInlineDatum(datum);
            if (withReferenceScript) {
                payment.setScriptRef(PlutusV3Script.builder()
                        .cborHex(SCRIPT_CODE_V3).build());
            }
            TransactionOutput change = TransactionOutput.builder()
                    .address(PAYER_ADDRESS)
                    .value(Value.builder().coin(BigInteger.valueOf(7_000_000L)).build()).build();
            TransactionBody.TransactionBodyBuilder body = TransactionBody.builder()
                    .inputs(inputs).outputs(List.of(payment, change))
                    .fee(BigInteger.valueOf(170_000L));
            if (ttlSlot != null) body.ttl(ttlSlot);
            Transaction tx = Transaction.builder().body(body.build())
                    .witnessSet(new TransactionWitnessSet()).build();
            Transaction signed = TransactionSigner.INSTANCE.sign(tx, PAYER_KEY);
            return Base64.getEncoder().encodeToString(signed.serialize());
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /**
     * Builds a signed preprod tx whose output-0 pays {@code payTo} the amount, optionally
     * carrying an inline datum and/or a datum hash, for the {@code script} method's S3
     * datum-policy tests. output-1 is the payer's change.
     */
    public static String buildScriptPaymentBase64(String payTo, BigInteger amount,
                                                  PlutusData inlineDatum, byte[] datumHash) {
        try {
            List<TransactionInput> inputs = new ArrayList<>();
            inputs.add(new TransactionInput(NONCE_TX_HASH, 0));
            TransactionOutput payment = TransactionOutput.builder()
                    .address(payTo)
                    .value(Value.builder().coin(amount).build()).build();
            if (inlineDatum != null) payment.setInlineDatum(inlineDatum);
            if (datumHash != null) payment.setDatumHash(datumHash);
            TransactionOutput change = TransactionOutput.builder()
                    .address(PAYER_ADDRESS)
                    .value(Value.builder().coin(BigInteger.valueOf(7_000_000L)).build()).build();
            TransactionBody body = TransactionBody.builder()
                    .inputs(inputs).outputs(List.of(payment, change))
                    .fee(BigInteger.valueOf(170_000L))
                    .ttl(1_000_000L)
                    .build();
            Transaction tx = Transaction.builder().body(body)
                    .witnessSet(new TransactionWitnessSet()).build();
            Transaction signed = TransactionSigner.INSTANCE.sign(tx, PAYER_KEY);
            return Base64.getEncoder().encodeToString(signed.serialize());
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /**
     * Builds the 19-field {@code Constr 0} masumi lock datum from a spec. Field order
     * mirrors datum.ts's {@code buildMasumiLockDatum}; {@code fieldCount}/{@code rootAlt}
     * let a test corrupt the outer shape.
     */
    public static PlutusData buildMasumiDatum(MasumiSpec spec) {
        List<PlutusData> fields = new ArrayList<>();
        fields.add(addressToDatum(spec.buyerAddress(), spec.buyerIsScript()));               // 0 buyer
        fields.add(ConstrPlutusData.of(1));                                                  // 1 buyer_return_address = None
        fields.add(addressToDatum(spec.sellerAddress(), spec.sellerIsScript()));             // 2 seller
        fields.add(ConstrPlutusData.of(1));                                                  // 3 seller_return_address = None
        fields.add(BytesPlutusData.of(HexUtil.decodeHexString(spec.referenceKeyHex())));     // 4 reference_key
        fields.add(BytesPlutusData.of(HexUtil.decodeHexString(spec.referenceSignatureHex()))); // 5 reference_signature
        fields.add(BytesPlutusData.of(HexUtil.decodeHexString(spec.sellerNonceHex())));      // 6 seller_nonce
        fields.add(BytesPlutusData.of(HexUtil.decodeHexString(spec.buyerNonceHex())));       // 7 buyer_nonce
        fields.add(BytesPlutusData.of(HexUtil.decodeHexString(spec.agentIdentifierHex())));  // 8 agent_identifier
        fields.add(BigIntPlutusData.of(spec.collateralReturnLovelace()));                    // 9 collateral_return_lovelace
        fields.add(BytesPlutusData.of(HexUtil.decodeHexString(spec.inputHashHex())));        // 10 input_hash
        fields.add(BytesPlutusData.of(HexUtil.decodeHexString(spec.resultHashHex())));       // 11 result_hash
        fields.add(BigIntPlutusData.of(spec.payByTime()));                                   // 12 pay_by_time
        fields.add(BigIntPlutusData.of(spec.submitResultTime()));                            // 13 submit_result_time
        fields.add(BigIntPlutusData.of(spec.unlockTime()));                                  // 14 unlock_time
        fields.add(BigIntPlutusData.of(spec.externalDisputeUnlockTime()));                   // 15 external_dispute_unlock_time
        fields.add(spec.cooldownCorrupt() ? ConstrPlutusData.of(0) : BigIntPlutusData.of(0)); // 16 seller_cooldown_time
        fields.add(BigIntPlutusData.of(0));                                                  // 17 buyer_cooldown_time
        fields.add(ConstrPlutusData.of(spec.stateAlt()));                                    // 18 state

        if (spec.fieldCount() < fields.size()) {
            fields = fields.subList(0, spec.fieldCount());
        } else {
            while (fields.size() < spec.fieldCount()) fields.add(BigIntPlutusData.of(0));
        }
        return ConstrPlutusData.of(spec.rootAlt(), fields.toArray(new PlutusData[0]));
    }

    /**
     * Encodes a bech32 address as the masumi {@code Address} datum shape:
     * {@code Constr 0 [paymentCred, stakeOption]}. {@code forceScript} lets a test claim
     * a script payment credential regardless of the address's real credential type.
     */
    private static PlutusData addressToDatum(String bech32, boolean forceScript) {
        Address addr = new Address(bech32);
        Credential paymentCred = addr.getPaymentCredential().orElseThrow();
        boolean isScript = forceScript || paymentCred.getType() == CredentialType.Script;
        PlutusData paymentData = ConstrPlutusData.of(isScript ? 1 : 0, BytesPlutusData.of(paymentCred.getBytes()));
        PlutusData stakeOption = addr.getDelegationCredentialHash()
                .<PlutusData>map(hash -> ConstrPlutusData.of(0,
                        ConstrPlutusData.of(0, ConstrPlutusData.of(0, BytesPlutusData.of(hash)))))
                .orElseGet(() -> ConstrPlutusData.of(1));
        return ConstrPlutusData.of(0, paymentData, stakeOption);
    }

    private TestTx() {}
}
