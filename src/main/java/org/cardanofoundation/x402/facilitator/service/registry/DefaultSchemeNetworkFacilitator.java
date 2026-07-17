package org.cardanofoundation.x402.facilitator.service.registry;

import org.cardanofoundation.x402.facilitator.model.protocol.PaymentPayload;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.model.protocol.SettleResponse;
import org.cardanofoundation.x402.facilitator.model.protocol.VerifyResponse;

import java.util.function.BiFunction;

/**
 * The facade (spec section 4.3): verify routes to the verification scheme,
 * settle routes to the settlement service. Both delegates are injected at
 * wiring time, keeping the dependency graph acyclic (SettlementService depends
 * one-way on the scheme for re-verification; the scheme never sees persistence).
 */
public class DefaultSchemeNetworkFacilitator implements SchemeNetworkFacilitator {

    /** CAIP-2 family pattern per x402 core v2 — used as the /supported signers key. */
    public static final String CAIP_FAMILY = "cardano:*";
    public static final String SCHEME = "exact";

    private final BiFunction<PaymentPayload, PaymentRequirements, VerifyResponse> verifyDelegate;
    private final BiFunction<PaymentPayload, PaymentRequirements, SettleResponse> settleDelegate;

    public DefaultSchemeNetworkFacilitator(
            BiFunction<PaymentPayload, PaymentRequirements, VerifyResponse> verifyDelegate,
            BiFunction<PaymentPayload, PaymentRequirements, SettleResponse> settleDelegate) {
        this.verifyDelegate = verifyDelegate;
        this.settleDelegate = settleDelegate;
    }

    @Override
    public String scheme() {
        return SCHEME;
    }

    @Override
    public String caipFamily() {
        return CAIP_FAMILY;
    }

    @Override
    public VerifyResponse verify(PaymentPayload payload, PaymentRequirements requirements) {
        return verifyDelegate.apply(payload, requirements);
    }

    @Override
    public SettleResponse settle(PaymentPayload payload, PaymentRequirements requirements) {
        return settleDelegate.apply(payload, requirements);
    }
}
