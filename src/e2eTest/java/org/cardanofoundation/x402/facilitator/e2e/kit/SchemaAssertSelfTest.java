package org.cardanofoundation.x402.facilitator.e2e.kit;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stackless: the settle schema accepts every shape the SettleResponse factories
 * produce and rejects the near misses a settlement regression would produce —
 * so a schema-valid body in the suite actually means something.
 */
class SchemaAssertSelfTest {

    private static final String TX = "a".repeat(64);

    @ParameterizedTest
    @ValueSource(strings = {
            // confirmed
            "{'success':true,'payer':'addr_test1x','transaction':'%TX','network':'cardano:preview','extra':{'status':'confirmed','transactionId':'%TX','confirmations':2}}",
            // mempool (accept-mempool)
            "{'success':true,'payer':'addr_test1x','transaction':'%TX','network':'cardano:preview','extra':{'status':'mempool','transactionId':'%TX','confirmations':-1}}",
            // pending, with and without observed confirmations
            "{'success':false,'errorReason':'settlement_pending','payer':'addr_test1x','transaction':'%TX','network':'cardano:preview','extra':{'status':'pending','transactionId':'%TX'}}",
            "{'success':false,'errorReason':'settlement_pending','payer':'addr_test1x','transaction':'%TX','network':'cardano:preview','extra':{'status':'pending','transactionId':'%TX','confirmations':-1}}",
            // expired
            "{'success':false,'errorReason':'settlement_failed','payer':'addr_test1x','transaction':'%TX','network':'cardano:preview','extra':{'status':'expired','transactionId':'%TX'}}",
            // definitively rejected after submission (no evidence)
            "{'success':false,'errorReason':'settlement_definitively_rejected','payer':'addr_test1x','transaction':'%TX','network':'cardano:preview'}",
            // rejected before submission
            "{'success':false,'errorReason':'invalid_exact_cardano_payload_unsigned','transaction':'','network':'cardano:preview'}"
    })
    void acceptsEveryFactoryShape(String body) {
        assertThatCode(() -> SchemaAssert.assertMatches(SchemaAssert.SETTLE, json(body))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // success with non-final evidence
            "{'success':true,'payer':'addr_test1x','transaction':'%TX','network':'cardano:preview','extra':{'status':'pending','transactionId':'%TX'}}",
            "{'success':true,'payer':'addr_test1x','transaction':'%TX','network':'cardano:preview','extra':{'status':'expired','transactionId':'%TX'}}",
            // success without evidence or payer
            "{'success':true,'payer':'addr_test1x','transaction':'%TX','network':'cardano:preview'}",
            "{'success':true,'transaction':'%TX','network':'cardano:preview','extra':{'status':'confirmed','transactionId':'%TX','confirmations':1}}",
            // confirmed without confirmations; mempool not -1
            "{'success':true,'payer':'addr_test1x','transaction':'%TX','network':'cardano:preview','extra':{'status':'confirmed','transactionId':'%TX'}}",
            "{'success':true,'payer':'addr_test1x','transaction':'%TX','network':'cardano:preview','extra':{'status':'mempool','transactionId':'%TX','confirmations':0}}",
            // pending without evidence, or with the wrong status
            "{'success':false,'errorReason':'settlement_pending','payer':'addr_test1x','transaction':'%TX','network':'cardano:preview'}",
            "{'success':false,'errorReason':'settlement_pending','payer':'addr_test1x','transaction':'%TX','network':'cardano:preview','extra':{'status':'confirmed','transactionId':'%TX','confirmations':1}}",
            // failure without a reason; success with a reason
            "{'success':false,'transaction':'','network':'cardano:preview'}",
            "{'success':true,'errorReason':'x','payer':'addr_test1x','transaction':'%TX','network':'cardano:preview','extra':{'status':'confirmed','transactionId':'%TX','confirmations':1}}",
            // evidence without a transaction; unknown field
            "{'success':false,'errorReason':'settlement_failed','transaction':'','network':'cardano:preview','extra':{'status':'expired','transactionId':'%TX'}}",
            "{'success':false,'errorReason':'x','transaction':'','network':'cardano:preview','surprise':1}"
    })
    void rejectsNearMisses(String body) {
        assertThatThrownBy(() -> SchemaAssert.assertMatches(SchemaAssert.SETTLE, json(body)))
                .isInstanceOf(AssertionError.class);
    }

    private static JsonNode json(String singleQuoted) throws Exception {
        return FacilitatorClient.JSON.readTree(singleQuoted.replace('\'', '"').replace("%TX", TX));
    }
}
