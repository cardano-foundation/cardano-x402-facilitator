package org.cardanofoundation.x402.facilitator.controller;

import org.cardanofoundation.x402.facilitator.model.ErrorCodes;
import org.cardanofoundation.x402.facilitator.model.protocol.SettleRequest;
import org.cardanofoundation.x402.facilitator.model.protocol.SettleResponse;
import org.cardanofoundation.x402.facilitator.model.protocol.SupportedResponse;
import org.cardanofoundation.x402.facilitator.model.protocol.VerifyRequest;
import org.cardanofoundation.x402.facilitator.service.registry.SchemeNetworkFacilitator;
import org.cardanofoundation.x402.facilitator.service.registry.X402FacilitatorRegistry;
import org.cardanofoundation.x402.facilitator.service.settlement.SettlementGate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
public class FacilitatorController {

    private final X402FacilitatorRegistry registry;
    private final SettlementGate settlementGate;

    public FacilitatorController(X402FacilitatorRegistry registry, SettlementGate settlementGate) {
        this.registry = registry;
        this.settlementGate = settlementGate;
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody VerifyRequest req) {
        if (req.paymentPayload() == null || req.paymentRequirements() == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing paymentPayload or paymentRequirements"));
        }
        Optional<SchemeNetworkFacilitator> handler = registry.find(
                req.paymentPayload().x402Version(),
                req.paymentRequirements().scheme(), req.paymentRequirements().network());
        // TS parity: core x402Facilitator throws for an unregistered (version, scheme,
        // network) and the reference facilitator surfaces that as HTTP 500 {"error"}.
        if (handler.isEmpty()) {
            return ResponseEntity.internalServerError().body(Map.of("error",
                    "No facilitator registered for scheme: " + req.paymentRequirements().scheme()
                            + " and network: " + req.paymentRequirements().network()));
        }
        return ResponseEntity.ok(handler.get().verify(req.paymentPayload(), req.paymentRequirements()));
    }

    @PostMapping("/settle")
    public ResponseEntity<?> settle(@RequestBody SettleRequest req) {
        if (req.paymentPayload() == null || req.paymentRequirements() == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing paymentPayload or paymentRequirements"));
        }
        Optional<SchemeNetworkFacilitator> handler = registry.find(
                req.paymentPayload().x402Version(),
                req.paymentRequirements().scheme(), req.paymentRequirements().network());
        if (handler.isEmpty()) {
            return ResponseEntity.internalServerError().body(Map.of("error",
                    "No facilitator registered for scheme: " + req.paymentRequirements().scheme()
                            + " and network: " + req.paymentRequirements().network()));
        }
        // Spec 13: don't accept a settlement we can't confirm — a blind backend
        // means submit-then-confirm is unreliable, so signal a retryable 503.
        if (!settlementGate.isHealthy(req.paymentRequirements().network())) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(SettleResponse.fail(
                    ErrorCodes.CHAIN_LOOKUP_FAILED, "settlement backend unhealthy",
                    req.paymentRequirements().network()));
        }
        return ResponseEntity.ok(handler.get().settle(req.paymentPayload(), req.paymentRequirements()));
    }

    @GetMapping("/supported")
    public SupportedResponse supported() {
        return registry.supported();
    }
}
