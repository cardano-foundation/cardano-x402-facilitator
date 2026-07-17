package org.cardanofoundation.x402.facilitator.model.protocol;

import java.util.List;
import java.util.Map;

public record SupportedResponse(List<SupportedKind> kinds, List<String> extensions, Map<String, List<String>> signers) {
}
