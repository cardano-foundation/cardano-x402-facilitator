package org.cardanofoundation.x402.facilitator.model.chain;

public record BackendHealth(boolean healthy, String detail) {

    public static BackendHealth ok() {
        return new BackendHealth(true, "ok");
    }

    public static BackendHealth down(String detail) {
        return new BackendHealth(false, detail);
    }
}
