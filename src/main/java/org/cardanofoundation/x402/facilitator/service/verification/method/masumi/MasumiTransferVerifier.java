package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import org.cardanofoundation.x402.facilitator.chain.NetworkClock;
import org.cardanofoundation.x402.facilitator.chain.ShelleyNetworkClock;
import org.cardanofoundation.x402.facilitator.model.ErrorCodes;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.model.verification.DecodedTransaction;
import org.cardanofoundation.x402.facilitator.service.registry.CardanoNetworks;
import org.cardanofoundation.x402.facilitator.service.verification.method.ExtraValues;
import org.cardanofoundation.x402.facilitator.service.verification.method.TransferMethodVerifier;
import org.cardanofoundation.x402.facilitator.service.verification.method.masumi.MasumiDatum.MasumiDatumView;
import org.cardanofoundation.x402.facilitator.service.verification.method.script.ScriptAddress;

import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code masumi} assetTransferMethod verifier (spec section 6, rules M1-M9).
 * Port of the TS reference's {@code verifyMasumiLock} ({@code exact/masumi/verify.ts}):
 * confirms a payment locks funds into the Masumi {@code vested_pay} escrow with a
 * well-formed {@code FundsLocked} datum matching the requirements. Only the
 * on-chain lock is checked (x402's scope); the post-lock lifecycle is out of
 * scope. Check order and error codes are wire-identical to the reference.
 *
 * <p>Two spec additions beyond the reference: M1 also validates {@code payTo}'s
 * script credential against a configured per-network allowlist (audit C1
 * remediation), and the M8 deadline honors a per-network slot-config override.
 */
public class MasumiTransferVerifier implements TransferMethodVerifier {

    private final Map<String, NetworkClock> clocksByNetwork;
    private final Map<String, Set<String>> allowedScriptHashesByNetwork;

    /** Test/default constructor: no allowlist (TS-parity), built-in slot configs. */
    public MasumiTransferVerifier() {
        this(Map.of(), Map.of());
    }

    /**
     * @param clocksByNetwork              network id -> configured clock (applies any
     *                                     per-network slot-config override); may be empty.
     * @param allowedScriptHashesByNetwork network id -> allowed escrow script-credential
     *                                     hashes (lowercase). Empty for a network = no
     *                                     allowlist enforcement (self-consistency only).
     */
    public MasumiTransferVerifier(Map<String, NetworkClock> clocksByNetwork,
                                  Map<String, Set<String>> allowedScriptHashesByNetwork) {
        this.clocksByNetwork = clocksByNetwork;
        this.allowedScriptHashesByNetwork = allowedScriptHashesByNetwork;
    }

    @Override
    public boolean supports(String method) {
        return "masumi".equals(method);
    }

    @Override
    public Optional<String> check(Map<String, Object> extra, PaymentRequirements requirements,
                                  DecodedTransaction tx, String payer, BigInteger coinsPerUtxoByte) {
        // 1. payTo MUST equal the deployment escrow the server declares in
        //    extra.contractAddress. Not defaulted: a wrong escrow strands funds.
        String contractAddress = str(extra, "contractAddress");
        if (contractAddress == null || !contractAddress.equals(requirements.payTo())) {
            return Optional.of(ErrorCodes.MASUMI_CONTRACT_MISMATCH);
        }
        // 1b. (spec addition, audit C1) When an allowlist is configured for this
        //     network, payTo MUST be a script address whose credential is allowed —
        //     otherwise a server could declare an arbitrary escrow and strand funds.
        Set<String> allowed = allowedScriptHashesByNetwork.get(CardanoNetworks.normalize(requirements.network()));
        if (allowed != null && !allowed.isEmpty()) {
            String credential = ScriptAddress.scriptPaymentCredentialHex(requirements.payTo());
            if (credential == null || !allowed.contains(credential)) {
                return Optional.of(ErrorCodes.MASUMI_CONTRACT_MISMATCH);
            }
        }

        // 2. Locate the escrow output paying payTo and carrying an inline datum.
        DecodedTransaction.Output output = null;
        for (DecodedTransaction.Output o : tx.outputs()) {
            if (o.address().equals(requirements.payTo()) && o.raw().getInlineDatum() != null) {
                output = o;
                break;
            }
        }
        if (output == null) return Optional.of(ErrorCodes.MASUMI_DATUM_MISSING);
        // The escrow output MUST NOT carry a reference script (Masumi treats a set
        // reference_script_hash as spoofing / FundsOrDatumInvalid).
        if (output.raw().getScriptRef() != null) return Optional.of(ErrorCodes.MASUMI_REFERENCE_SCRIPT);

        PlutusData datum = output.raw().getInlineDatum();
        MasumiDatumView view = MasumiDatum.parse(datum);
        if (view == null) return Optional.of(ErrorCodes.MASUMI_DATUM_INVALID);

        // 3. Structural invariants of a fresh lock.
        if (view.state() != MasumiDatum.STATE_FUNDS_LOCKED) return Optional.of(ErrorCodes.MASUMI_DATUM_INVALID);
        if (!view.resultHash().isEmpty()) return Optional.of(ErrorCodes.MASUMI_DATUM_INVALID);
        // Fresh lock: both cooldown timers MUST be 0 (a non-zero value is spoofing).
        if (view.sellerCooldownTime().signum() != 0 || view.buyerCooldownTime().signum() != 0) {
            return Optional.of(ErrorCodes.MASUMI_DATUM_INVALID);
        }
        if (view.buyer().payment().isScript() || view.seller().payment().isScript()) {
            return Optional.of(ErrorCodes.MASUMI_DATUM_INVALID);
        }
        // reference_signature: >= 16 bytes (32 hex chars).
        if (view.referenceSignature().length() < 32) return Optional.of(ErrorCodes.MASUMI_DATUM_INVALID);
        // Time ordering: pay_by <= submit_result <= unlock <= external_dispute_unlock.
        if (view.payByTime().compareTo(view.submitResultTime()) > 0
                || view.submitResultTime().compareTo(view.unlockTime()) > 0
                || view.unlockTime().compareTo(view.externalDisputeUnlockTime()) > 0) {
            return Optional.of(ErrorCodes.MASUMI_DATUM_INVALID);
        }

        // 4. Deadline: the tx MUST carry a validity upper bound (TTL) on/before
        //    pay_by_time. Uses the per-network configured clock (slot-config override).
        if (tx.ttlSlot() == null) return Optional.of(ErrorCodes.MASUMI_DEADLINE);
        long ttlPosixMs = clockFor(requirements.network()).slotToTime(tx.ttlSlot()).toEpochMilli();
        if (BigInteger.valueOf(ttlPosixMs).compareTo(view.payByTime()) > 0) {
            return Optional.of(ErrorCodes.MASUMI_DEADLINE);
        }

        // 5. Value: collateral bounds + asset/amount (mirrors checkPaymentAmountsMatch).
        BigInteger collateral = view.collateralReturnLovelace();
        if (collateral.signum() < 0
                || (collateral.signum() > 0 && collateral.compareTo(MasumiConstants.MASUMI_MIN_COLLATERAL_LOVELACE) < 0)
                || collateral.compareTo(output.coin()) > 0) {
            return Optional.of(ErrorCodes.MASUMI_COLLATERAL);
        }
        BigInteger amount = new BigInteger(requirements.amount());
        String assetKey = requirements.asset() == null ? "" : requirements.asset().toLowerCase();
        boolean isLovelace = "lovelace".equals(assetKey);
        if (isLovelace) {
            if (output.coin().compareTo(amount.add(collateral)) < 0) return Optional.of(ErrorCodes.MASUMI_ASSET);
        } else {
            BigInteger held = output.assets().get(assetKey);
            if (held == null || held.compareTo(amount) != 0) return Optional.of(ErrorCodes.MASUMI_ASSET);
        }
        if (output.assets().size() != (isLovelace ? 0 : 1)) return Optional.of(ErrorCodes.MASUMI_ASSET);

        // 6. min-UTXO with post-result headroom, computed on the ON-CHAIN datum byte
        //    length (TS datumHex.length/2), not cardano-client-lib's re-serialization.
        if (coinsPerUtxoByte != null) {
            int nativeTokenCount = output.assets().size();
            BigInteger requiredMinUtxo = MasumiConstants.masumiMinUtxoLovelace(
                    output.inlineDatumRawLen(), nativeTokenCount, coinsPerUtxoByte);
            if (output.coin().compareTo(requiredMinUtxo) < 0) return Optional.of(ErrorCodes.MASUMI_MIN_UTXO);
        }

        // 7. Field matching against the canonical requirements' extra.
        if (!MasumiDatum.sameCredentials(view.buyer(), MasumiDatum.addressCredentials(payer))) {
            return Optional.of(ErrorCodes.MASUMI_DATUM_MISMATCH);
        }
        String sellerAddress = str(extra, "sellerAddress");
        if (sellerAddress == null
                || !MasumiDatum.sameCredentials(view.seller(), MasumiDatum.addressCredentials(sellerAddress))) {
            return Optional.of(ErrorCodes.MASUMI_DATUM_MISMATCH);
        }
        if (!MasumiDatum.returnAddressMatches(str(extra, "buyerReturnAddress"), view.buyerReturnAddress())
                || !MasumiDatum.returnAddressMatches(str(extra, "sellerReturnAddress"), view.sellerReturnAddress())) {
            return Optional.of(ErrorCodes.MASUMI_DATUM_MISMATCH);
        }
        if (hexMismatch(extra, "referenceKey", view.referenceKey())
                || hexMismatch(extra, "referenceSignature", view.referenceSignature())
                || hexMismatch(extra, "sellerNonce", view.sellerNonce())
                || hexMismatch(extra, "identifierFromPurchaser", view.buyerNonce())
                || hexMismatch(extra, "agentIdentifier", view.agentIdentifier())
                || hexMismatch(extra, "inputHash", view.inputHash())) {
            return Optional.of(ErrorCodes.MASUMI_DATUM_MISMATCH);
        }
        if (intMismatch(extra, "payByTime", view.payByTime())
                || intMismatch(extra, "submitResultTime", view.submitResultTime())
                || intMismatch(extra, "unlockTime", view.unlockTime())
                || intMismatch(extra, "externalDisputeUnlockTime", view.externalDisputeUnlockTime())
                || intMismatch(extra, "collateralReturnLovelace", view.collateralReturnLovelace())) {
            return Optional.of(ErrorCodes.MASUMI_DATUM_MISMATCH);
        }
        return Optional.empty();
    }

    private NetworkClock clockFor(String network) {
        NetworkClock clock = clocksByNetwork.get(CardanoNetworks.normalize(network));
        return clock != null ? clock : ShelleyNetworkClock.forNetwork(network, null);
    }

    private static String str(Map<String, Object> extra, String key) {
        Object v = extra == null ? null : extra.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** True when a declared hex field is present and does NOT match the datum value. */
    private static boolean hexMismatch(Map<String, Object> extra, String key, String actualLowerHex) {
        String declared = str(extra, key);
        return declared != null && !declared.toLowerCase().equals(actualLowerHex);
    }

    /** True when a declared integer field is present and does NOT match the datum value. */
    private static boolean intMismatch(Map<String, Object> extra, String key, BigInteger actual) {
        Object declared = extra == null ? null : extra.get(key);
        return declared != null && !ExtraValues.toBigInteger(declared).equals(actual);
    }
}
