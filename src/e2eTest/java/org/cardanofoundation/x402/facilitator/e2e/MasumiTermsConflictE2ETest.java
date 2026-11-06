package org.cardanofoundation.x402.facilitator.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.cardanofoundation.x402.facilitator.e2e.kit.DevnetKit;
import org.cardanofoundation.x402.facilitator.e2e.kit.FacilitatorClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.Funding;
import org.cardanofoundation.x402.facilitator.e2e.kit.MasumiLock;
import org.cardanofoundation.x402.facilitator.e2e.kit.PaymentFactory;
import org.cardanofoundation.x402.facilitator.e2e.kit.SchemaAssert;
import org.cardanofoundation.x402.facilitator.e2e.kit.ShimClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.StackRequired;
import org.cardanofoundation.x402.facilitator.e2e.kit.TransferAssert;
import org.cardanofoundation.x402.facilitator.e2e.kit.Wallet;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Masumi terms are claimable once (unique {@code terms_digest}, ADR 2026-10-09): two
 * transactions from two wallets lock under the SAME seller-signed terms; the first
 * settles, the second is a {@code duplicate_settlement} that is never broadcast.
 * The terms carry a unique seller nonce so no other class can collide with them.
 *
 * <p>Expected codes are string literals: ErrorCodeCoverageTest checks this source.
 */
@StackRequired
class MasumiTermsConflictE2ETest {

    @Test
    void aSecondTransactionUnderAlreadyClaimedTermsIsADuplicateSettlement() {
        DevnetKit kit = DevnetKit.fromEnv();
        PaymentFactory payments = new PaymentFactory(kit);
        MasumiLock.Deployment deployment = MasumiLock.deployments().allowlisted();

        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String sellerNonce = HexFormat.of().formatHex(random);
        Wallet firstPayer = Funding.wallet(30);
        Wallet secondPayer = Funding.wallet(30);

        MasumiLock first = new MasumiLock(kit, payments, deployment);
        first.preSign = extra -> MasumiLock.terms(extra).put("sellerNonce", sellerNonce);
        MasumiLock.Built firstBuilt = first.build(firstPayer);

        // The second lock reuses every term of the first, so the same seller authorization covers it.
        Map<String, Object> sharedTerms = new LinkedHashMap<>(MasumiLock.terms(firstBuilt.extra()));
        MasumiLock second = new MasumiLock(kit, payments, deployment);
        second.preSign = extra -> MasumiLock.terms(extra).putAll(sharedTerms);
        MasumiLock.Built secondBuilt = second.build(secondPayer);

        assertThat(MasumiLock.terms(secondBuilt.extra())).isEqualTo(sharedTerms);
        assertThat(secondBuilt.extra().get("referenceSignature")).isEqualTo(firstBuilt.extra().get("referenceSignature"));
        assertThat(secondBuilt.payment().txHash()).isNotEqualTo(firstBuilt.payment().txHash());

        TransferAssert.assertVerifiedAndSettled(firstBuilt.payment());

        FacilitatorClient.Response settle = FacilitatorClient.defaultInstance().settle(secondBuilt.payment().body());
        assertThat(settle.status()).as("%s", settle).isEqualTo(200);
        JsonNode settled = SchemaAssert.assertMatches(SchemaAssert.SETTLE, settle);
        assertThat(settled.path("success").asBoolean()).as("%s", settle).isFalse();
        assertThat(settled.path("errorReason").asText()).as("%s", settle).isEqualTo("duplicate_settlement");
        assertThat(settled.path("errorMessage").asText()).as("%s", settle)
                .isEqualTo("Masumi terms already claimed by another transaction");
        assertThat(settled.path("transaction").asText()).isEmpty();

        assertThat(ShimClient.fromEnv().submitCount(secondBuilt.payment().txBytes())).isZero();
        assertThat(kit.isOnChain(secondBuilt.payment().txHash())).isFalse();
    }
}
