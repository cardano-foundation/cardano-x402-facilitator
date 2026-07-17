package org.cardanofoundation.x402.facilitator.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FacilitatorControllerTest {

    @Autowired
    MockMvc mvc;

    private static final String REQUIREMENTS = """
            {"scheme":"exact","network":"cardano:preprod","asset":"lovelace",
             "amount":"1500000","payTo":"addr_test1x","maxTimeoutSeconds":600,
             "extra":{"assetTransferMethod":"default"}}""";

    @Test
    void supportedAdvertisesCanonicalKindsAndCaipFamilySigners() throws Exception {
        mvc.perform(get("/supported"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kinds[0].x402Version").value(2))
                .andExpect(jsonPath("$.kinds[0].scheme").value("exact"))
                .andExpect(jsonPath("$.kinds[0].network").value("cardano:preprod"))
                .andExpect(jsonPath("$.extensions").isEmpty())
                .andExpect(jsonPath("$.signers['cardano:*']").isEmpty());
    }

    @Test
    void verifyRejectsMissingFieldsWith400() throws Exception {
        mvc.perform(post("/verify").contentType(APPLICATION_JSON).content("{\"x402Version\":2}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verifyUnregisteredNetworkIs500WithError() throws Exception {
        String body = """
                {"x402Version":2,
                 "paymentPayload":{"x402Version":2,"accepted":%s,
                   "payload":{"transaction":"AAAA","nonce":"aa#0"}},
                 "paymentRequirements":%s}"""
                .formatted(REQUIREMENTS.replace("cardano:preprod", "cardano:mainnet"),
                        REQUIREMENTS.replace("cardano:preprod", "cardano:mainnet"));
        mvc.perform(post("/verify").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void malformedJsonIs400NotStackTrace() throws Exception {
        mvc.perform(post("/verify").contentType(APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Malformed request body"));
    }

    @Test
    void healthSummarizesNetworks() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.networks[0].id").value("cardano:preprod"));
    }

    @Test
    void oversizedContentLengthIs413() throws Exception {
        byte[] big = new byte[70000];
        java.util.Arrays.fill(big, (byte) 'a');
        mvc.perform(post("/verify").contentType(APPLICATION_JSON).content(big))
                .andExpect(status().isPayloadTooLarge());
    }
}
