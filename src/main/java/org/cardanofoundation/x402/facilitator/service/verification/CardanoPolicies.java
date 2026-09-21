package org.cardanofoundation.x402.facilitator.service.verification;

import java.util.Map;

/** Canonical confirmation policy shared by verification and settlement. Submission is always facilitator-owned. */
public final class CardanoPolicies {

    public static final int MIN_L1_CONFIRMATIONS = -1;
    public static final int MAX_L1_CONFIRMATIONS = 20;
    public static final int DEFAULT_L1_CONFIRMATIONS = 1;

    private CardanoPolicies() {
    }

    /**
     * Reads {@code extra.confirmationPolicy.l1Confirmations}, a closed object whose
     * only member is an integer from -1 through 20. Absent is one confirmation.
     *
     * @param extra the requirements' extra block, possibly null.
     * @return the required confirmations, or null when the policy is malformed.
     */
    public static Integer l1Confirmations(Map<String, Object> extra) {
        if (extra == null || !extra.containsKey("confirmationPolicy")) return DEFAULT_L1_CONFIRMATIONS;
        Object declared = extra.get("confirmationPolicy");
        if (!(declared instanceof Map<?, ?> policy)) return null;
        if (policy.size() != 1 || !policy.containsKey("l1Confirmations")) return null;
        Object value = policy.get("l1Confirmations");
        if (!(value instanceof Number n)) return null;
        int confirmations;
        try { confirmations = new java.math.BigDecimal(n.toString()).intValueExact(); }
        catch (NumberFormatException | ArithmeticException e) { return null; }
        if (confirmations < MIN_L1_CONFIRMATIONS || confirmations > MAX_L1_CONFIRMATIONS) return null;
        return confirmations;
    }

    /**
     * Whether observed evidence meets a required threshold. {@code -1} is
     * authenticated mempool acceptance, {@code 0} canonical inclusion, and
     * {@code n} that many newer canonical blocks; stronger evidence always
     * satisfies a weaker requirement.
     *
     * @param observed the strongest verified evidence level.
     * @param required the threshold from the confirmation policy.
     * @return true when the evidence suffices.
     */
    public static boolean confirmationsSatisfy(int observed, int required) {
        return observed >= required;
    }
}
