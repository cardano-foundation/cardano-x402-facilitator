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
        Map<String, Object> evidence = status == null ? null : "mempool".equals(status)
                ? Map.of("status", status, "transactionId", txHash, "confirmations", -1)
                : Map.of("status", status, "transactionId", txHash);
        return new SettleResponse(true, null, null, payer, txHash, network, evidence);
    }

    public static SettleResponse confirmed(String txHash, String network, String payer, int confirmations) {
        return new SettleResponse(true, null, null, payer, txHash, network,
                Map.of("status", "confirmed", "transactionId", txHash, "confirmations", confirmations));
    }

    public static SettleResponse pending(String txHash, String network, String payer) {
        return pending(txHash, network, payer, null);
    }

    public static SettleResponse pending(String txHash, String network, String payer, Integer confirmations) {
        Map<String, Object> evidence = confirmations == null
                ? Map.of("status", "pending", "transactionId", txHash)
                : Map.of("status", "pending", "transactionId", txHash, "confirmations", confirmations);
        return new SettleResponse(false, "settlement_pending", null, payer, txHash, network, evidence);
    }

    public static SettleResponse fail(String reason, String message, String network) {
        return new SettleResponse(false, reason, message, null, "", network, null);
    }

    public static SettleResponse failWithTx(String reason, String txHash, String network,
                                            String payer, String status) {
        return new SettleResponse(false, reason, null, payer, txHash, network,
                status == null ? null : Map.of("status", status, "transactionId", txHash));
    }
}
