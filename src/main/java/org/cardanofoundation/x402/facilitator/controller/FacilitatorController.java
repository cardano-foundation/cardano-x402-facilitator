package org.cardanofoundation.x402.facilitator.controller;

import org.cardanofoundation.x402.facilitator.model.ErrorCodes;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentPayload;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.model.protocol.SettleRequest;
import org.cardanofoundation.x402.facilitator.model.protocol.SettleResponse;
import org.cardanofoundation.x402.facilitator.model.protocol.SupportedResponse;
import org.cardanofoundation.x402.facilitator.model.protocol.VerifyRequest;
import org.cardanofoundation.x402.facilitator.service.registry.SchemeNetworkFacilitator;
import org.cardanofoundation.x402.facilitator.service.registry.X402FacilitatorRegistry;
import org.cardanofoundation.x402.facilitator.model.protocol.VerifyResponse;
import org.cardanofoundation.x402.facilitator.service.settlement.SettlementGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * A rejection is otherwise invisible: the reason travels only in the response
 * body, and the x402 middlewares collapse it into a bare {@code 402 {}} before
 * it reaches the client. Logging it at the controller — the one point every
 * request passes through — is what makes a failed payment diagnosable.
 */
@RestController
@RequiredArgsConstructor
@Log4j2
public class FacilitatorController {

    private final X402FacilitatorRegistry registry;
    private final SettlementGate settlementGate;

    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody VerifyRequest req) {
        if (req.paymentPayload() == null || req.paymentRequirements() == null) {
            return missingFields();
        }
        Optional<SchemeNetworkFacilitator> handler = handlerFor(req.paymentPayload(), req.paymentRequirements());
        if (handler.isEmpty()) {
            return unregistered(req.paymentRequirements());
        }
        VerifyResponse res = handler.get().verify(req.paymentPayload(), req.paymentRequirements());
        if (res.isValid()) {
            log.info("verify OK payer={} {}", res.payer(), describe(req.paymentPayload(), req.paymentRequirements()));
        } else {
            log.warn("verify REJECTED reason={} message={} {}", res.invalidReason(), res.invalidMessage(),
                    describe(req.paymentPayload(), req.paymentRequirements()));
        }
        return ResponseEntity.ok(res);
    }

    @PostMapping("/settle")
    public ResponseEntity<?> settle(@RequestBody SettleRequest req) {
        if (req.paymentPayload() == null || req.paymentRequirements() == null) {
            return missingFields();
        }
        Optional<SchemeNetworkFacilitator> handler = handlerFor(req.paymentPayload(), req.paymentRequirements());
        if (handler.isEmpty()) {
            return unregistered(req.paymentRequirements());
        }
        // Don't accept a settlement we can't confirm — a blind backend means
        // submit-then-confirm is unreliable, so signal a retryable 503.
        if (!settlementGate.isHealthy(req.paymentRequirements().network())) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(SettleResponse.fail(
                    ErrorCodes.CHAIN_LOOKUP_FAILED, "settlement backend unhealthy",
                    req.paymentRequirements().network()));
        }
        SettleResponse res = handler.get().settle(req.paymentPayload(), req.paymentRequirements());
        if (res.success()) {
            log.info("settle OK tx={} {}", res.transaction(),
                    describe(req.paymentPayload(), req.paymentRequirements()));
        } else {
            log.warn("settle FAILED reason={} message={} tx={} {}", res.errorReason(), res.errorMessage(),
                    res.transaction(), describe(req.paymentPayload(), req.paymentRequirements()));
        }
        return ResponseEntity.ok(res);
    }

    /**
     * Compact request context for a log line: everything needed to identify the
     * payment, minus the multi-kilobyte signed transaction blob.
     *
     * @param payload      the payment payload under judgement.
     * @param requirements the canonical requirements it was judged against.
     * @return a single-line summary.
     */
    private static String describe(PaymentPayload payload, PaymentRequirements requirements) {
        Object method = requirements.extra() == null ? null : requirements.extra().get("assetTransferMethod");
        Object nonce = payload.payload() == null ? null : payload.payload().get("nonce");
        return String.format("[scheme=%s network=%s amount=%s asset=%s payTo=%s method=%s nonce=%s]",
                requirements.scheme(), requirements.network(), requirements.amount(), requirements.asset(),
                requirements.payTo(), method == null ? "default" : method, nonce);
    }

    @GetMapping("/supported")
    public SupportedResponse supported() {
        return registry.supported();
    }

    private Optional<SchemeNetworkFacilitator> handlerFor(PaymentPayload payload, PaymentRequirements requirements) {
        return registry.find(payload.x402Version(), requirements.scheme(), requirements.network());
    }

    private static ResponseEntity<Map<String, String>> missingFields() {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Missing paymentPayload or paymentRequirements"));
    }

    private static ResponseEntity<Map<String, String>> unregistered(PaymentRequirements requirements) {
        // An unregistered (version, scheme, network) combination is reported
        // as HTTP 500 with an {"error"} body.
        return ResponseEntity.internalServerError().body(Map.of("error",
                "No facilitator registered for scheme: " + requirements.scheme()
                        + " and network: " + requirements.network()));
    }
}
