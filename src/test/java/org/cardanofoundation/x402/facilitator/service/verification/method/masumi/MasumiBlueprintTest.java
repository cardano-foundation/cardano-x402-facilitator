package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-implementation compatibility for the Masumi escrow derivation.
 *
 * <p>The address here is pinned by the spec (see the identifier test vectors in
 * {@code scheme_exact_cardano.md}) and asserted identically by the TypeScript
 * reference implementation. If Java and TypeScript ever disagree about where the
 * escrow lives, a buyer following one and a facilitator following the other
 * would disagree about whether a lock is valid — so this is the single most
 * load-bearing assertion in the Masumi port.
 */
class MasumiBlueprintTest {

    /** Escrow address the spec's identifier vectors are built against. */
    private static final String SPEC_ESCROW =
            "addr_test1wzs4e6wc95hkwezlccjw9mdvq0r0rsgx6zk34avptga3ftgn37w4g";

    @Test
    void derivesTheSpecEscrowAddressFromTheDefaultDeployment() {
        assertThat(MasumiBlueprint.escrowAddress("cardano:preprod", MasumiBlueprint.DEFAULT_DEPLOYMENT))
                .isEqualTo(SPEC_ESCROW);
    }

    @Test
    void differentParameterizationIsADifferentEscrow() {
        MasumiBlueprint.MasumiDeployment custom = new MasumiBlueprint.MasumiDeployment(
                BigInteger.ONE,
                MasumiBlueprint.DEFAULT_DEPLOYMENT.adminVkeys(),
                MasumiBlueprint.DEFAULT_DEPLOYMENT.cooldownPeriod());
        assertThat(MasumiBlueprint.escrowAddress("cardano:preprod", custom))
                .isNotEqualTo(SPEC_ESCROW);
    }

    @Test
    void duplicatedAdminKeyIsADistinctDeployment() {
        // A repeated key carries repeated voting weight, so `[x, x, y]` is not
        // the same trust domain as `[x, y]` even at the same threshold.
        List<String> duplicated = List.of(
                MasumiBlueprint.DEFAULT_DEPLOYMENT.adminVkeys().get(0),
                MasumiBlueprint.DEFAULT_DEPLOYMENT.adminVkeys().get(0),
                MasumiBlueprint.DEFAULT_DEPLOYMENT.adminVkeys().get(1));
        MasumiBlueprint.MasumiDeployment weighted = new MasumiBlueprint.MasumiDeployment(
                MasumiBlueprint.DEFAULT_DEPLOYMENT.requiredAdmins(),
                duplicated,
                MasumiBlueprint.DEFAULT_DEPLOYMENT.cooldownPeriod());
        assertThat(MasumiBlueprint.escrowScriptHash(weighted))
                .isNotEqualTo(MasumiBlueprint.escrowScriptHash(MasumiBlueprint.DEFAULT_DEPLOYMENT));
    }

    @Test
    void mainnetAndPreprodShareAHashButNotAnAddress() {
        String mainnet = MasumiBlueprint.escrowAddress("cardano:mainnet", MasumiBlueprint.DEFAULT_DEPLOYMENT);
        assertThat(mainnet).startsWith("addr1w").isNotEqualTo(SPEC_ESCROW);
    }
}
