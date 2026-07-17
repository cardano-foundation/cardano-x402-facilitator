package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.address.CredentialType;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;
import java.util.List;

/**
 * Codec + typed view for the Masumi {@code vested_pay} escrow lock datum
 * (payment-v2 / {@code Web3CardanoV2}). Port of the TS reference
 * ({@code exact/masumi/datum.ts}) restricted to the parse + credential helpers
 * the facilitator needs.
 *
 * <p>All datum fields are compared STRUCTURALLY (credential-hash hex / BigInteger)
 * and never by datum-hex: Evolution (the frontend) emits indefinite-length CBOR
 * while cardano-client-lib re-serializes definite-length, so hex equality would
 * spuriously fail even for an identical datum.
 *
 * <p>{@link #parse} is total: it returns {@code null} whenever the structure does
 * not match the 19-field {@code Constr 0} datum (mirroring the TS
 * {@code parseMasumiLockDatum} contract).
 */
public final class MasumiDatum {

    /** The state constructor index for a fresh lock ({@code FundsLocked}). */
    public static final long STATE_FUNDS_LOCKED = 0L;

    /** A payment or stake credential: whether it is a script hash, and the hash hex. */
    public record MasumiCredential(boolean isScript, String hash) {
    }

    /**
     * Address split into its payment credential plus its stake-hash-or-"".
     * The stake credential's script-ness is intentionally NOT tracked: the TS
     * {@code sameCredentials} compares only the stake hash (audit parity).
     */
    public record MasumiAddressCredentials(MasumiCredential payment, String stakeHash) {
    }

    /** Decoded view of a lock datum used by the facilitator. */
    public record MasumiDatumView(
            MasumiAddressCredentials buyer,
            MasumiAddressCredentials buyerReturnAddress, // null == datum None
            MasumiAddressCredentials seller,
            MasumiAddressCredentials sellerReturnAddress, // null == datum None
            String referenceKey,
            String referenceSignature,
            String sellerNonce,
            String buyerNonce,
            String agentIdentifier,
            BigInteger collateralReturnLovelace,
            String inputHash,
            String resultHash,
            BigInteger payByTime,
            BigInteger submitResultTime,
            BigInteger unlockTime,
            BigInteger externalDisputeUnlockTime,
            BigInteger sellerCooldownTime,
            BigInteger buyerCooldownTime,
            long state) {
    }

    /** {@code Some(addr)} / {@code None}; a null OptionResult means structural mismatch. */
    private record OptionResult(MasumiAddressCredentials value) {
    }

    /**
     * Extracts the payment + optional stake credential of a bech32 address.
     * The stake credential collapses to its lowercase hash hex ("" when absent).
     */
    public static MasumiAddressCredentials addressCredentials(String bech32) {
        Address addr = new Address(bech32);
        Credential pc = addr.getPaymentCredential().orElseThrow();
        MasumiCredential payment = new MasumiCredential(
                pc.getType() == CredentialType.Script,
                HexUtil.encodeHexString(pc.getBytes()).toLowerCase());
        String stakeHash = addr.getDelegationCredentialHash()
                .map(b -> HexUtil.encodeHexString(b).toLowerCase())
                .orElse("");
        return new MasumiAddressCredentials(payment, stakeHash);
    }

    /** True when two addresses share the same payment (and stake, if any) credential. */
    public static boolean sameCredentials(MasumiAddressCredentials a, MasumiAddressCredentials b) {
        if (a.payment().isScript() != b.payment().isScript()) return false;
        if (!a.payment().hash().equals(b.payment().hash())) return false;
        return a.stakeHash().equals(b.stakeHash());
    }

    /**
     * True when the datum's optional return address exactly matches the declared
     * one: a declared address must be present in the datum with matching
     * credentials; an omitted one ({@code declared == null}) must be datum
     * {@code None} ({@code actual == null}).
     */
    public static boolean returnAddressMatches(String declared, MasumiAddressCredentials actual) {
        if (declared == null) return actual == null;
        return actual != null && sameCredentials(actual, addressCredentials(declared));
    }

    /**
     * Parses a Masumi lock datum ({@code Constr 0}, 19 fields) into a typed view.
     * Returns {@code null} on any structural mismatch.
     */
    public static MasumiDatumView parse(PlutusData datum) {
        try {
            if (!(datum instanceof ConstrPlutusData root)) return null;
            if (root.getAlternative() != 0) return null;
            List<PlutusData> f = root.getData().getPlutusDataList();
            if (f.size() != 19) return null;

            MasumiAddressCredentials buyer = parseAddress(f.get(0));
            OptionResult buyerReturn = parseOptionAddress(f.get(1));
            MasumiAddressCredentials seller = parseAddress(f.get(2));
            OptionResult sellerReturn = parseOptionAddress(f.get(3));
            String referenceKey = hex(f.get(4));
            String referenceSignature = hex(f.get(5));
            String sellerNonce = hex(f.get(6));
            String buyerNonce = hex(f.get(7));
            String agentIdentifier = hex(f.get(8));
            BigInteger collateral = intVal(f.get(9));
            String inputHash = hex(f.get(10));
            String resultHash = hex(f.get(11));
            BigInteger payByTime = intVal(f.get(12));
            BigInteger submitResultTime = intVal(f.get(13));
            BigInteger unlockTime = intVal(f.get(14));
            BigInteger externalDisputeUnlockTime = intVal(f.get(15));
            BigInteger sellerCooldownTime = intVal(f.get(16));
            BigInteger buyerCooldownTime = intVal(f.get(17));
            Long state = constrIndex(f.get(18));

            if (buyer == null || buyerReturn == null || seller == null || sellerReturn == null
                    || referenceKey == null || referenceSignature == null || sellerNonce == null
                    || buyerNonce == null || agentIdentifier == null || collateral == null
                    || inputHash == null || resultHash == null || payByTime == null
                    || submitResultTime == null || unlockTime == null || externalDisputeUnlockTime == null
                    || sellerCooldownTime == null || buyerCooldownTime == null || state == null) {
                return null;
            }

            return new MasumiDatumView(buyer, buyerReturn.value(), seller, sellerReturn.value(),
                    referenceKey, referenceSignature, sellerNonce, buyerNonce, agentIdentifier,
                    collateral, inputHash, resultHash, payByTime, submitResultTime, unlockTime,
                    externalDisputeUnlockTime, sellerCooldownTime, buyerCooldownTime, state);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Decodes a masumi {@code Address} datum ({@code Constr 0 [paymentCred, stakeOption]}). */
    private static MasumiAddressCredentials parseAddress(PlutusData d) {
        if (!(d instanceof ConstrPlutusData addr) || addr.getAlternative() != 0) return null;
        List<PlutusData> fields = addr.getData().getPlutusDataList();
        if (fields.size() != 2) return null;
        MasumiCredential payment = parseCredential(fields.get(0));
        if (payment == null) return null;

        if (!(fields.get(1) instanceof ConstrPlutusData opt)) return null;
        if (opt.getAlternative() == 1 && opt.getData().getPlutusDataList().isEmpty()) {
            return new MasumiAddressCredentials(payment, ""); // None
        }
        if (opt.getAlternative() != 0 || opt.getData().getPlutusDataList().size() != 1) return null;
        if (!(opt.getData().getPlutusDataList().get(0) instanceof ConstrPlutusData inline)
                || inline.getAlternative() != 0
                || inline.getData().getPlutusDataList().size() != 1) {
            return null; // Inline(cred)
        }
        MasumiCredential stake = parseCredential(inline.getData().getPlutusDataList().get(0));
        if (stake == null) return null;
        return new MasumiAddressCredentials(payment, stake.hash());
    }

    /** Decodes an {@code Option<Address>} field ({@code Some(addr)} / {@code None}). */
    private static OptionResult parseOptionAddress(PlutusData d) {
        if (!(d instanceof ConstrPlutusData opt)) return null;
        List<PlutusData> fields = opt.getData().getPlutusDataList();
        if (opt.getAlternative() == 1 && fields.isEmpty()) return new OptionResult(null); // None
        if (opt.getAlternative() != 0 || fields.size() != 1) return null; // Some(addr)
        MasumiAddressCredentials addr = parseAddress(fields.get(0));
        return addr == null ? null : new OptionResult(addr);
    }

    /** Decodes a Plutus credential ({@code Constr 0|1 [bytes]}) into a typed credential. */
    private static MasumiCredential parseCredential(PlutusData d) {
        if (!(d instanceof ConstrPlutusData c)) return null;
        long alt = c.getAlternative();
        if (alt != 0 && alt != 1) return null;
        List<PlutusData> fields = c.getData().getPlutusDataList();
        if (fields.size() != 1) return null;
        String hash = hex(fields.get(0));
        if (hash == null) return null;
        return new MasumiCredential(alt == 1, hash);
    }

    private static String hex(PlutusData d) {
        return d instanceof BytesPlutusData b ? HexUtil.encodeHexString(b.getValue()).toLowerCase() : null;
    }

    private static BigInteger intVal(PlutusData d) {
        return d instanceof BigIntPlutusData i ? i.getValue() : null;
    }

    private static Long constrIndex(PlutusData d) {
        return d instanceof ConstrPlutusData c ? c.getAlternative() : null;
    }

    private MasumiDatum() {
    }
}
