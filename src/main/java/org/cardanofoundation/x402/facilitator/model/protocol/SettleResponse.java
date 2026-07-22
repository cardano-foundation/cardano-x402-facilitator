package org.cardanofoundation.x402.facilitator.model.protocol;

import java.util.Map;

/**
 * transaction/network are ALWAYS non-null: "" when nothing was submitted,
 * the real tx hash whenever a submission happened.
 */
public record SettleResponse(boolean success, String errorReason, String errorMessage,
                             String payer, String transaction, String network,
                             Map<String, Object> extra) {

    public static SettleResponse ok(String txHash, String network, String payer, String status) {
        return new SettleResponse(true, null, null, payer, txHash, network,
                status == null ? null : Map.of("status", status));
    }

    public static SettleResponse fail(String reason, String message, String network) {
        return new SettleResponse(false, reason, message, null, "", network, null);
    }

    public static SettleResponse failWithTx(String reason, String txHash, String network,
                                            String payer, String status) {
        return new SettleResponse(false, reason, null, payer, txHash, network,
                status == null ? null : Map.of("status", status));
    }
}
