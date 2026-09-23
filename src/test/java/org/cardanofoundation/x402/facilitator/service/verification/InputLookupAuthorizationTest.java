package org.cardanofoundation.x402.facilitator.service.verification;

import org.cardanofoundation.x402.facilitator.chain.ShelleyNetworkClock;
import org.cardanofoundation.x402.facilitator.model.ErrorCodes;
import org.cardanofoundation.x402.facilitator.model.chain.UtxoState;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentPayload;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.service.verification.decoder.CardanoTransactionDecoder;
import org.cardanofoundation.x402.facilitator.service.verification.method.DefaultTransferVerifier;
import org.cardanofoundation.x402.facilitator.testutil.FakeChainService;
import org.cardanofoundation.x402.facilitator.testutil.TestTx;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class InputLookupAuthorizationTest {
    @Test void rejectsUnauthorizedNonceBeforeLookingUpOtherInputs() {
        var lookups = new ArrayList<String>();
        var chain = new FakeChainService() {
            @Override public UtxoState getUtxoState(String hash, int index) {
                lookups.add(hash + "#" + index);
                return super.getUtxoState(hash, index);
            }
        };
        chain.unspent.put(TestTx.NONCE, TestTx.PAY_TO); // Signer does not own the nonce.
        String extraHash = "cd".repeat(32);
        chain.unspent.put(extraHash + "#0", TestTx.PAYER_ADDRESS);
        var tx = TestTx.buildBase64(TestTx.Spec.defaults().withExtraInputs(List.of(
                TransactionInput.builder().transactionId(extraHash).index(0).build())));
        var requirements = new PaymentRequirements("exact", "cardano:preprod", "lovelace", "2000000", TestTx.PAY_TO,
                600, Map.of("assetTransferMethod", "default"));
        var scheme = new ExactCardanoScheme(chain, chain, new CardanoTransactionDecoder(),
                List.of(new DefaultTransferVerifier()), 32768, ShelleyNetworkClock.forNetwork("cardano:preprod", null));
        var result = scheme.verify(new PaymentPayload(2, null, requirements,
                Map.of("transaction", tx, "nonce", TestTx.NONCE), null), requirements);
        assertThat(result.invalidReason()).isEqualTo(ErrorCodes.PAYER_NOT_WITNESS);
        assertThat(lookups).containsExactly(TestTx.NONCE);
    }
}
