package org.cardanofoundation.x402.facilitator.model.protocol;

public record SettleRequest(Integer x402Version, PaymentPayload paymentPayload, PaymentRequirements paymentRequirements) {
}
