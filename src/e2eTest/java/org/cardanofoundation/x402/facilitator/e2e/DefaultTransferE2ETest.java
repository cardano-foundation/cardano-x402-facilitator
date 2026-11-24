package org.cardanofoundation.x402.facilitator.e2e;

import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import org.cardanofoundation.x402.facilitator.e2e.kit.DevnetKit;
import org.cardanofoundation.x402.facilitator.e2e.kit.Funding;
import org.cardanofoundation.x402.facilitator.e2e.kit.Payment;
import org.cardanofoundation.x402.facilitator.e2e.kit.PaymentFactory;
import org.cardanofoundation.x402.facilitator.e2e.kit.StackRequired;
import org.cardanofoundation.x402.facilitator.e2e.kit.TransferAssert;
import org.cardanofoundation.x402.facilitator.e2e.kit.Wallet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.x402.facilitator.e2e.kit.TransferAssert.decode;

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
    private static Wallet receiver;

    @BeforeAll
    static void stack() {
        kit = DevnetKit.fromEnv();
        payments = new PaymentFactory(kit);
        receiver = Funding.wallet(5);
    }

    @Test
    void adaPayment() {
        Wallet payer = Funding.wallet(20);
        assertVerifiedAndSettled(payments.from(payer).payTo(receiver).lovelace(2_000_000).build());
    }

    @Test
    void nativeTokenPayment() {
        Wallet payer = Funding.wallet(20);
        String asset = kit.mintToken(payer, "E2ETOKEN", 1_000);
        Payment payment = payments.from(payer).payTo(receiver).token(asset, 250).build();
        assertThat(payment.requirements().get("asset")).isEqualTo(asset);
        assertVerifiedAndSettled(payment);
    }

    @Test
    void paymentWithAuxiliaryMetadata() {
        Wallet payer = Funding.wallet(20);
        Metadata metadata = MetadataBuilder.createMetadata();
        metadata.put(BigInteger.valueOf(674), "x402 devnet e2e");
        Payment payment = payments.from(payer).payTo(receiver).lovelace(2_000_000).metadata(metadata).build();
        // Really an aux-data tx, with the body committing to it - not a plain ADA payment.
        Transaction tx = decode(payment.txBytes());
        assertThat(tx.getAuxiliaryData()).isNotNull();
        assertThat(tx.getBody().getAuxiliaryDataHash()).isNotNull();
        assertVerifiedAndSettled(payment);
    }

    @Test
    void overpayment() {
        Wallet payer = Funding.wallet(20);
        Payment payment = payments.from(payer).payTo(receiver).lovelace(2_000_000).send(3_500_000).build();
        assertThat(payment.requirements().get("amount")).isEqualTo("2000000");
        assertThat(decode(payment.txBytes()).getBody().getOutputs())
                .filteredOn(o -> o.getAddress().equals(receiver.address()))
                .extracting(o -> o.getValue().getCoin())
                .containsExactly(BigInteger.valueOf(3_500_000));
        assertVerifiedAndSettled(payment);
    }

    private static void assertVerifiedAndSettled(Payment payment) {
        TransferAssert.assertVerifiedAndSettled(payment, settled -> {
            assertThat(settled.path("network").asText()).isEqualTo(DevnetKit.NETWORK);
            // No confirmationPolicy in the requirements means one confirmation.
            assertThat(settled.path("extra").path("confirmations").asInt()).isGreaterThanOrEqualTo(1);
        });
    }
}
