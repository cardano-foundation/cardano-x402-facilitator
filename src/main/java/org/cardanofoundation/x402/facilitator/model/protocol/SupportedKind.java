package org.cardanofoundation.x402.facilitator.model.protocol;

import java.util.Map;

public record SupportedKind(int x402Version, String scheme, String network,
                            Map<String, Object> extra) {
}
