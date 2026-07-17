package org.cardanofoundation.x402.facilitator.chain;

/** A chain lookup failed or the backing view is stale — verification must fail closed. */
public class ChainLookupException extends RuntimeException {

    public ChainLookupException(String message) {
        super(message);
    }

    public ChainLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
