package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import java.util.Map;

/** Independently authenticates a Masumi registry claim on the selected network, including its endpoint. */
@FunctionalInterface
public interface MasumiRegistryValidator {
    boolean validate(Context context);

    record Context(PaymentRequirements requirements, Map<String, Object> terms,
                   Map<String, Object> resource, String network) { }
}
