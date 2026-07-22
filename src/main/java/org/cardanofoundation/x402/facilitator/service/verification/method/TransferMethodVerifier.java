package org.cardanofoundation.x402.facilitator.service.verification.method;

import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.model.verification.DecodedTransaction;

import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;

/**
 * Extension point for assetTransferMethod-specific checks (default/masumi/script).
 * Implementations are Spring beans, collected in list order (Stage E).
 */
public interface TransferMethodVerifier {

    boolean supports(String method);

    /**
     * @param coinsPerUtxoByte live protocol parameter, forwarded to methods that
     *                         need a min-UTXO computation (Masumi M9); may be null.
     * @return an error code when the check fails, empty when it passes.
     */
    Optional<String> check(Map<String, Object> extra, PaymentRequirements requirements,
                           DecodedTransaction tx, String payer, BigInteger coinsPerUtxoByte);

    /** Canonical (lowercase) asset key of the requirement; {@code ""} for lovelace's absent asset. */
    static String assetKey(PaymentRequirements requirements) {
        return requirements.asset() == null ? "" : requirements.asset().toLowerCase();
    }

    /** True when the requirement's asset is lovelace (ADA), given its {@link #assetKey}. */
    static boolean isLovelace(String assetKey) {
        return "lovelace".equals(assetKey);
    }
}
