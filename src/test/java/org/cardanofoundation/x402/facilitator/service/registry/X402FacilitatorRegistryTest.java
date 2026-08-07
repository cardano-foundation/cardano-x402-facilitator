package org.cardanofoundation.x402.facilitator.service.registry;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.cardanofoundation.x402.facilitator.model.protocol.*;
import static org.assertj.core.api.Assertions.assertThat;

class X402FacilitatorRegistryTest {
    private final SchemeNetworkFacilitator exact = new SchemeNetworkFacilitator() {
        public String scheme() { return "exact"; }
        public String caipFamily() { return "cardano:*"; }
        public VerifyResponse verify(PaymentPayload p, PaymentRequirements r) { return VerifyResponse.valid("x"); }
        public SettleResponse settle(PaymentPayload p, PaymentRequirements r) { return SettleResponse.ok("h", r.network(), "x", "confirmed"); }
    };

    @Test void findsByVersionSchemeAndNormalizedNetwork() {
        X402FacilitatorRegistry reg = new X402FacilitatorRegistry();
        reg.register("cardano:preprod", exact);
        assertThat(reg.find(2, "exact", "cardano:preprod")).isPresent();
        assertThat(reg.find(2, "exact", "cip34:0-1")).isPresent();    // alias normalized
        assertThat(reg.find(2, "exact", "cardano:mainnet")).isEmpty();
        assertThat(reg.find(1, "exact", "cardano:preprod")).isEmpty(); // v2 only
        assertThat(reg.find(2, "upto", "cardano:preprod")).isEmpty();
    }

    @Test void supportedAdvertisesCanonicalKindAndEmptySigners() {
        X402FacilitatorRegistry reg = new X402FacilitatorRegistry();
        reg.register("cardano:preprod", exact);
        SupportedResponse s = reg.supported();
        // The advertised capabilities are the contract a resource server checks
        // its selected policies against, so assert them rather than echo them.
        Map<String, Object> expectedExtra = new LinkedHashMap<>();
        expectedExtra.put("assetTransferMethods", List.of("default", "masumi", "script"));
        expectedExtra.put("settlementLayers", List.of("l1"));
        expectedExtra.put("areFeesSponsored", false);
        expectedExtra.put("submissionModes", List.of("server", "client"));
        // No mempool opt-in, so the floor is canonical inclusion for both modes.
        Map<String, Object> range = Map.of("minimum", 0, "maximum", 20);
        expectedExtra.put("l1Confirmations", Map.of("server", range, "client", range));
        assertThat(s.kinds())
                .containsExactly(new SupportedKind(2, "exact", "cardano:preprod", expectedExtra));
        assertThat(s.extensions()).isEmpty();
        assertThat(s.signers()).containsEntry("cardano:*", List.of());
    }

    @Test void advertisedConfirmationFloorFollowsTheMempoolOptIn() {
        // A facilitator that will settle on mempool evidence must say so, or a
        // resource server can never quote the -1 the operator enabled.
        X402FacilitatorRegistry reg = new X402FacilitatorRegistry(true);
        reg.register("cardano:preprod", exact);
        Map<?, ?> extra = (Map<?, ?>) reg.supported().kinds().get(0).extra();
        Map<?, ?> ranges = (Map<?, ?>) extra.get("l1Confirmations");
        assertThat(((Map<?, ?>) ranges.get("server")).get("minimum")).isEqualTo(-1);
        assertThat(((Map<?, ?>) ranges.get("client")).get("minimum")).isEqualTo(-1);
    }
}
