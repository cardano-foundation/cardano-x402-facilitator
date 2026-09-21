package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.service.verification.decoder.CardanoTransactionDecoder;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MasumiUpstreamCompatibilityTest {
    @Test void acceptsActualAdaAndNativeTokenLocksBuiltAndSignedByPinnedTypeScript() throws Exception {
        var json = new ObjectMapper();
        int checked = 0;
        for (var payment : MasumiDigestsTest.fixtures().get("payments")) {
            if (!payment.get("name").asText().startsWith("masumi-")) continue;
            var req = json.treeToValue(payment.get("requirements"), PaymentRequirements.class);
            var tx = new CardanoTransactionDecoder().decode(payment.path("payload").path("payload").path("transaction").asText());
            assertThat(tx.signaturesValid()).as(payment.get("name").asText()).isTrue();
            assertThat(new MasumiTransferVerifier().check(req.extra(), req, tx, payment.get("payer").asText(), BigInteger.valueOf(4310)))
                    .as(payment.get("name").asText()).isEmpty();
            checked++;
        }
        assertThat(checked).isEqualTo(2);
    }
}
