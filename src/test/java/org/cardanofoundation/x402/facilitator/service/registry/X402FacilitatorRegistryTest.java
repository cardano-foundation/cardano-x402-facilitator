package org.cardanofoundation.x402.facilitator.service.registry;

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
        assertThat(s.kinds()).containsExactly(new SupportedKind(2, "exact", "cardano:preprod"));
        assertThat(s.extensions()).isEmpty();
        assertThat(s.signers()).containsEntry("cardano:*", java.util.List.of());
    }
}
