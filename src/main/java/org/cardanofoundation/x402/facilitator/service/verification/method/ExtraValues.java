package org.cardanofoundation.x402.facilitator.service.verification.method;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * JS-semantics coercions for {@code extra} / parameter values, which arrive as
 * loosely-typed JSON scalars (String or Number). The TS reference uses
 * {@code BigInt(value)} and JS truthiness; these helpers reproduce both exactly
 * so a value that the reference accepts is not spuriously rejected here.
 */
public final class ExtraValues {

    /**
     * Mirrors JS {@code BigInt(value)}: an integer-valued Number converts, a
     * non-integer Number throws; an integer String converts, a non-integer
     * String ("42.0") throws. Matches the reference for both encodings.
     */
    public static BigInteger toBigInteger(Object value) {
        if (value == null) throw new NumberFormatException("null");
        if (value instanceof Number n) {
            // BigInt(42.0) === 42n; BigInt(42.5) throws. toBigIntegerExact() throws on non-integers.
            return new BigDecimal(n.toString()).toBigIntegerExact();
        }
        // BigInt("42") === 42n; BigInt("42.0") throws — new BigInteger(String) matches.
        return new BigInteger(String.valueOf(value));
    }

    /**
     * Mirrors JS truthiness for {@code value ? 1n : 0n}: null/false/0/NaN/"" are
     * falsy; every non-empty string (including "false"), non-zero number, and any
     * object is truthy.
     */
    public static boolean jsTruthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return d != 0.0 && !Double.isNaN(d);
        }
        if (value instanceof String s) return !s.isEmpty();
        return true;
    }

    private ExtraValues() {
    }
}
