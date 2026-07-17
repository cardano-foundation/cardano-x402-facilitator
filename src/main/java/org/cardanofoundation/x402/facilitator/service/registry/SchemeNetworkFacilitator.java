package org.cardanofoundation.x402.facilitator.service.registry;

import org.cardanofoundation.x402.facilitator.model.protocol.PaymentPayload;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.model.protocol.SettleResponse;
import org.cardanofoundation.x402.facilitator.model.protocol.VerifyResponse;

public interface SchemeNetworkFacilitator {

    String scheme();

    String caipFamily();

    VerifyResponse verify(PaymentPayload payload, PaymentRequirements requirements);

    SettleResponse settle(PaymentPayload payload, PaymentRequirements requirements);
}
