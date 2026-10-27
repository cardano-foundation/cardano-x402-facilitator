package org.cardanofoundation.x402.facilitator.e2e;

import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.fasterxml.jackson.databind.JsonNode;
import org.cardanofoundation.x402.facilitator.e2e.kit.DevnetKit;
import org.cardanofoundation.x402.facilitator.e2e.kit.FacilitatorClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.Payment;
import org.cardanofoundation.x402.facilitator.e2e.kit.PaymentFactory;
import org.cardanofoundation.x402.facilitator.e2e.kit.SchemaAssert;
import org.cardanofoundation.x402.facilitator.e2e.kit.ShimClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.StackRequired;
import org.cardanofoundation.x402.facilitator.e2e.kit.Wallet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The positive vectors of the default transfer method, each proven end to end:
 * /verify accepts it with the right payer, /settle confirms exactly the locally
 * hashed transaction, the facilitator broadcast it once, and Yano — not the
 * facilitator — shows it in a block.
 */
@StackRequired
class DefaultTransferE2ETest {

    private static DevnetKit kit;
    private static PaymentFactory payments;
    private static FacilitatorClient facilitator;
    private static Wallet receiver;

    @BeforeAll
    static void stack() {
        kit = DevnetKit.fromEnv();
        payments = new PaymentFactory(kit);
        facilitator = FacilitatorClient.defaultInstance();
        receiver = kit.newWallet(5);
    }

    @Test
    void adaPayment() {
        Wallet payer = kit.newWallet(20);
        assertVerifiedAndSettled(payments.from(payer).payTo(receiver).lovelace(2_000_000).build());
    }

    @Test
    void nativeTokenPayment() {
        Wallet payer = kit.newWallet(20);
        String asset = kit.mintToken(payer, "E2ETOKEN", 1_000);
        Payment payment = payments.from(payer).payTo(receiver).token(asset, 250).build();
        assertThat(payment.requirements().get("asset")).isEqualTo(asset);
        assertVerifiedAndSettled(payment);
    }

    @Test
    void paymentWithAuxiliaryMetadata() {
        Wallet payer = kit.newWallet(20);
        Metadata metadata = MetadataBuilder.createMetadata();
        metadata.put(BigInteger.valueOf(674), "x402 devnet e2e");
        Payment payment = payments.from(payer).payTo(receiver).lovelace(2_000_000).metadata(metadata).build();
        // Really an aux-data tx, with the body committing to it - not a plain ADA payment.
        Transaction tx = decode(payment);
        assertThat(tx.getAuxiliaryData()).isNotNull();
        assertThat(tx.getBody().getAuxiliaryDataHash()).isNotNull();
        assertVerifiedAndSettled(payment);
    }

    @Test
    void overpayment() {
        Wallet payer = kit.newWallet(20);
        Payment payment = payments.from(payer).payTo(receiver).lovelace(2_000_000).send(3_500_000).build();
        assertThat(payment.requirements().get("amount")).isEqualTo("2000000");
        assertThat(decode(payment).getBody().getOutputs())
                .filteredOn(o -> o.getAddress().equals(receiver.address()))
                .extracting(o -> o.getValue().getCoin())
                .containsExactly(BigInteger.valueOf(3_500_000));
        assertVerifiedAndSettled(payment);
    }

    private static Transaction decode(Payment payment) {
        try {
            return Transaction.deserialize(payment.txBytes());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertVerifiedAndSettled(Payment payment) {
        FacilitatorClient.Response verify = facilitator.verify(payment.body());
        assertThat(verify.status()).as("%s", verify).isEqualTo(200);
        JsonNode verified = SchemaAssert.assertMatches(SchemaAssert.VERIFY, verify);
        assertThat(verified.path("isValid").asBoolean()).as("%s", verify).isTrue();
        assertThat(verified.path("payer").asText()).isEqualTo(payment.payer().address());

        FacilitatorClient.Response settle = facilitator.settle(payment.body());
        assertThat(settle.status()).as("%s", settle).isEqualTo(200);
        JsonNode settled = SchemaAssert.assertMatches(SchemaAssert.SETTLE, settle);
        assertThat(settled.path("success").asBoolean()).as("%s", settle).isTrue();
        assertThat(settled.path("transaction").asText()).isEqualTo(payment.txHash());
        assertThat(settled.path("payer").asText()).isEqualTo(payment.payer().address());
        assertThat(settled.path("network").asText()).isEqualTo(DevnetKit.NETWORK);
        assertThat(settled.path("extra").path("status").asText()).isEqualTo("confirmed");
        // No confirmationPolicy in the requirements means one confirmation.
        assertThat(settled.path("extra").path("confirmations").asInt()).isGreaterThanOrEqualTo(1);

        kit.assertOnChain(payment.txHash());
        assertThat(ShimClient.fromEnv().submitCount(payment.txBytes())).isEqualTo(1);
    }
}
