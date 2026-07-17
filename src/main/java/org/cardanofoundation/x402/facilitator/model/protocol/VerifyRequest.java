package org.cardanofoundation.x402.facilitator.model.protocol;

public record VerifyRequest(Integer x402Version, PaymentPayload paymentPayload, PaymentRequirements paymentRequirements) {
}
