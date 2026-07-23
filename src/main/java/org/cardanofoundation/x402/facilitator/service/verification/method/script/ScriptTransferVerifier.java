package org.cardanofoundation.x402.facilitator.service.verification.method.script;

import org.cardanofoundation.x402.facilitator.model.ErrorCodes;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.model.verification.DecodedTransaction;
import org.cardanofoundation.x402.facilitator.service.verification.method.TransferMethodVerifier;

import lombok.RequiredArgsConstructor;

import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code script} assetTransferMethod verifier (rules S1-S3).
 *
 * <ul>
 *   <li><b>S1</b> — the reconstructed script hash (from {@code script}+parameters,
 *       or {@code scriptHash}) MUST equal {@code payTo}'s script credential.</li>
 *   <li><b>S2</b> — asset/amount/min-UTXO: enforced by the shared Stage E checks.</li>
 *   <li><b>S3</b> — the {@code script-datum-policy} controls how strictly the
 *       escrow output's datum is checked. Datum <em>contents</em> are never
 *       validated (contract-specific):
 *     <ul>
 *       <li>{@code reference} (default) — mirrors the TS reference facilitator:
 *           no datum-kind checks, EXCEPT a {@code plutusV1} script whose locked
 *           output carries an INLINE datum is rejected. The ledger cannot
 *           represent an inline datum in a Plutus V1 script context, so such an
 *           output is unspendable by that script — the one guaranteed-stranding
 *           case, which the scheme spec explicitly permits rejecting.</li>
 *       <li>{@code strict} — Plutus-version- and datum-kind-aware: PlutusV1
 *           requires a datum HASH; PlutusV2 requires an inline datum or a datum
 *           hash; PlutusV3 requires one too; an unknown version
 *           ({@code scriptHash}-only) requires SOME datum.</li>
 *       <li>{@code v3-optional} — same as {@code strict}, except PlutusV3 also
 *           allows a datum-less output.</li>
 *     </ul>
 *   </li>
 * </ul>
 */
@RequiredArgsConstructor
public class ScriptTransferVerifier implements TransferMethodVerifier {

    /** {@code "reference"} (default) | {@code "strict"} | {@code "v3-optional"}. */
    private final String datumPolicy;

    @Override
    public boolean supports(String method) {
        return "script".equals(method);
    }

    @Override
    public Optional<String> check(Map<String, Object> extra, PaymentRequirements requirements,
                                  DecodedTransaction tx, String payer, BigInteger coinsPerUtxoByte) {
        // S1: a script descriptor MUST be present, and the reconstructed script
        //     hash MUST equal payTo's script credential.
        boolean hasDescriptor = extra != null && (extra.get("scriptHash") != null || extra.get("script") != null);
        if (!hasDescriptor || !ScriptAddress.scriptAddressMatches(extra, requirements.payTo())) {
            return Optional.of(ErrorCodes.SCRIPT_ADDRESS_MISMATCH);
        }

        // S3: datum policy on the escrow output paying payTo.
        DecodedTransaction.Output locked = lockedOutput(requirements, tx);
        if (locked == null) return Optional.of(ErrorCodes.SCRIPT_DATUM_MISSING);
        boolean hasInline = locked.raw().getInlineDatum() != null;
        boolean hasHash = locked.raw().getDatumHash() != null;
        String version = plutusVersion(extra);

        if ("reference".equals(datumPolicy)) {
            // The TS reference facilitator performs no datum-kind checks. The one
            // exception: a PlutusV1 script can never spend an inline datum (the
            // ledger has no representation for it in a V1 script context), so
            // such an output is guaranteed unspendable and safe to reject.
            boolean v1InlineUnspendable = "plutusV1".equals(version) && hasInline;
            return v1InlineUnspendable ? Optional.of(ErrorCodes.SCRIPT_DATUM_MISSING) : Optional.empty();
        }

        boolean datumMissing = switch (version) {
            // PlutusV1 predates inline datums: only a datum HASH makes the output spendable.
            case "plutusV1" -> !hasHash;
            case "plutusV2" -> !hasInline && !hasHash;
            case "plutusV3" -> !hasInline && !hasHash && !"v3-optional".equals(datumPolicy);
            // scriptHash-only (language unknowable): require SOME datum.
            default -> !hasInline && !hasHash;
        };
        return datumMissing ? Optional.of(ErrorCodes.SCRIPT_DATUM_MISSING) : Optional.empty();
    }

    /** The Plutus version declared by {@code extra.script.type}, or "unknown". */
    private static String plutusVersion(Map<String, Object> extra) {
        Object scriptObj = extra == null ? null : extra.get("script");
        if (scriptObj instanceof Map<?, ?> script && script.get("type") != null) {
            return String.valueOf(script.get("type"));
        }
        return "unknown";
    }

    /** First payTo output that satisfies the requested asset/amount (Stage E selection). */
    private static DecodedTransaction.Output lockedOutput(PaymentRequirements requirements, DecodedTransaction tx) {
        BigInteger amount = new BigInteger(requirements.amount());
        String assetKey = TransferMethodVerifier.assetKey(requirements);
        boolean isLovelace = TransferMethodVerifier.isLovelace(assetKey);
        for (DecodedTransaction.Output o : tx.outputs()) {
            if (!o.address().equals(requirements.payTo())) continue;
            BigInteger available = isLovelace ? o.coin() : o.assets().get(assetKey);
            if (available != null && available.compareTo(amount) >= 0) return o;
        }
        return null;
    }
}
