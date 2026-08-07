package org.cardanofoundation.x402.facilitator.service.verification;

import java.util.Map;

/**
 * The shared submission and confirmation policies every Cardano
 * assetTransferMethod carries at the top level of {@code extra}.
 *
 * <p>Both are bound by exact {@code accepted} matching and neither is part of
 * the Masumi {@code termsDigest}: they describe how this payment settles, not
 * what was agreed. Absent values normalize to the spec defaults ({@code server},
 * one confirmation) rather than being treated as unconstrained.
 *
 * <p>Mirrors {@code policy.ts} in the TypeScript reference implementation.
 */
public final class CardanoPolicies {

    public static final String SUBMISSION_SERVER = "server";
    public static final String SUBMISSION_CLIENT = "client";
    public static final String SUBMISSION_EITHER = "either";

    public static final int MIN_L1_CONFIRMATIONS = -1;
    public static final int MAX_L1_CONFIRMATIONS = 20;
    public static final int DEFAULT_L1_CONFIRMATIONS = 1;

    private CardanoPolicies() {
    }

    /**
     * Normalizes {@code extra.submissionPolicy}. Absent is {@code server}.
     *
     * @param extra the requirements' extra block, possibly null.
     * @return the policy, or null when the declared value is not one of the three literals.
     */
    public static String submissionPolicy(Map<String, Object> extra) {
        Object declared = extra == null ? null : extra.get("submissionPolicy");
        if (declared == null) return SUBMISSION_SERVER;
        String value = String.valueOf(declared);
        return switch (value) {
            case SUBMISSION_SERVER, SUBMISSION_CLIENT, SUBMISSION_EITHER -> value;
            default -> null;
        };
    }

    /**
     * Normalizes {@code payload.submissionMode}. Absent is {@code server};
     * {@code either} is a policy and is never a valid payload mode.
     *
     * @param payload the payment payload map, possibly null.
     * @return the mode, or null when the declared value is neither literal.
     */
    public static String submissionMode(Map<String, Object> payload) {
        Object declared = payload == null ? null : payload.get("submissionMode");
        if (declared == null) return SUBMISSION_SERVER;
        String value = String.valueOf(declared);
        return switch (value) {
            case SUBMISSION_SERVER, SUBMISSION_CLIENT -> value;
            default -> null;
        };
    }

    /**
     * Whether a policy admits a normalized payload mode.
     *
     * @param policy the declared requirements policy.
     * @param mode the normalized payload mode.
     * @return true when the mode is selectable under the policy.
     */
    public static boolean modeAllowed(String policy, String mode) {
        return SUBMISSION_EITHER.equals(policy) || policy.equals(mode);
    }

    /**
     * Reads {@code extra.confirmationPolicy.l1Confirmations}, a closed object whose
     * only member is an integer from -1 through 20. Absent is one confirmation.
     *
     * @param extra the requirements' extra block, possibly null.
     * @return the required confirmations, or null when the policy is malformed.
     */
    public static Integer l1Confirmations(Map<String, Object> extra) {
        Object declared = extra == null ? null : extra.get("confirmationPolicy");
        if (declared == null) return DEFAULT_L1_CONFIRMATIONS;
        if (!(declared instanceof Map<?, ?> policy)) return null;
        if (policy.size() != 1 || !policy.containsKey("l1Confirmations")) return null;
        Object value = policy.get("l1Confirmations");
        if (!(value instanceof Number n) || n.doubleValue() != Math.floor(n.doubleValue())) return null;
        int confirmations = n.intValue();
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
