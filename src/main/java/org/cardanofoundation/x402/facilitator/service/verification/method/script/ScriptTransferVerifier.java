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
 *   <li><b>S3</b> — Plutus-version- and datum-kind-aware datum policy: without it
 *       a payment could lock funds to an output the seller's script can never
 *       spend. PlutusV1 requires a datum HASH; PlutusV2 requires an inline datum
 *       or a datum hash; PlutusV3 allows a datum-less output only under
 *       {@code script-datum-policy: v3-optional}; an unknown version
 *       ({@code scriptHash}-only) requires SOME datum. Datum <em>contents</em>
 *       are never validated (contract-specific).</li>
 * </ul>
 */
@RequiredArgsConstructor
public class ScriptTransferVerifier implements TransferMethodVerifier {

    private final boolean v3DatumOptional;

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
        boolean datumMissing = switch (version) {
            // PlutusV1 predates inline datums: only a datum HASH makes the output spendable.
            case "plutusV1" -> !hasHash;
            case "plutusV2" -> !hasInline && !hasHash;
            case "plutusV3" -> !hasInline && !hasHash && !v3DatumOptional;
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
