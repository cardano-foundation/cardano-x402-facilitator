package org.cardanofoundation.x402.facilitator.model.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ProtocolJsonTest {
    private final ObjectMapper mapper = ProtocolJson.mapper();

    @Test
    void deserializesVerifyRequest() throws Exception {
        String json = """
            {"x402Version":2,
             "paymentPayload":{"x402Version":2,
               "accepted":{"scheme":"exact","network":"cardano:preprod","asset":"lovelace",
                           "amount":"2000000","payTo":"addr_test1abc","maxTimeoutSeconds":600,"extra":{}},
               "payload":{"transaction":"AAAA","nonce":"aa#0"},
               "unknownField":true},
             "paymentRequirements":{"scheme":"exact","network":"cardano:preprod","asset":"lovelace",
               "amount":"2000000","payTo":"addr_test1abc","maxTimeoutSeconds":600,
               "extra":{"assetTransferMethod":"default"}}}
            """;
        VerifyRequest req = mapper.readValue(json, VerifyRequest.class);
        assertThat(req.paymentPayload().accepted().network()).isEqualTo("cardano:preprod");
        assertThat(req.paymentPayload().payload().get("nonce")).isEqualTo("aa#0");
        assertThat(req.paymentRequirements().extra().get("assetTransferMethod")).isEqualTo("default");
    }

    @Test
    void settleFailureAlwaysCarriesTransactionAndNetwork() throws Exception {
        SettleResponse r = SettleResponse.fail("exact_cardano_settlement_failed", "boom", "cardano:preprod");
        String json = mapper.writeValueAsString(r);
        assertThat(json).contains("\"transaction\":\"\"").contains("\"network\":\"cardano:preprod\"");
        assertThat(json).doesNotContain("payer"); // NON_NULL: optional fields omitted
    }

    @Test
    void settleOkCarriesTransactionNetworkAndStatus() throws Exception {
        SettleResponse r = SettleResponse.ok("abcd1234", "cardano:preprod", "addr_test1payer", "confirmed");
        String json = mapper.writeValueAsString(r);
        assertThat(json).contains("\"transaction\":\"abcd1234\"")
                .contains("\"network\":\"cardano:preprod\"")
                .contains("\"status\":\"confirmed\"");
    }

    @Test
    void settleFailWithTxCarriesTransactionNetworkAndStatus() throws Exception {
        SettleResponse r = SettleResponse.failWithTx("exact_cardano_settlement_failed",
                "abcd1234", "cardano:preprod", "addr_test1payer", "mempool");
        String json = mapper.writeValueAsString(r);
        assertThat(json).contains("\"transaction\":\"abcd1234\"")
                .contains("\"network\":\"cardano:preprod\"")
                .contains("\"status\":\"mempool\"");
    }

    @Test
    void settleNullStatusOmitsExtraAndDoesNotThrow() throws Exception {
        SettleResponse ok = SettleResponse.ok("abcd1234", "cardano:preprod", "addr_test1payer", null);
        SettleResponse withTx = SettleResponse.failWithTx("exact_cardano_settlement_failed",
                "abcd1234", "cardano:preprod", "addr_test1payer", null);
        assertThat(mapper.writeValueAsString(ok)).doesNotContain("extra").doesNotContain("status");
        assertThat(mapper.writeValueAsString(withTx)).doesNotContain("extra").doesNotContain("status");
    }

    @Test
    void verifyValidOmitsInvalidReason() throws Exception {
        String json = mapper.writeValueAsString(VerifyResponse.valid("addr_test1payer"));
        assertThat(json).contains("\"isValid\":true").contains("\"payer\":\"addr_test1payer\"");
        assertThat(json).doesNotContain("invalidReason");
    }
}
