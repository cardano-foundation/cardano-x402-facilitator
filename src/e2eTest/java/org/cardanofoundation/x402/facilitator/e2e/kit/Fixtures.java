package org.cardanofoundation.x402.facilitator.e2e.kit;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.VerificationKey;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.script.NativeScript;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;

import java.time.Duration;
import java.util.Optional;

/**
 * Small, named chain state for the transaction-shape rows: each fixture leaves something
 * on the devnet (or builds a key-bound object) that a shape needs and QuickTx cannot invent.
 * Every fixture takes the devnet kit and works on wallets of its own, so rows stay independent.
 */
public final class Fixtures {

    private static final long REFERENCE_UTXO_ADA = 2;
    private static final Duration CHAIN_DEADLINE = Duration.ofSeconds(120);

    private Fixtures() {
    }

    /** A second, separate UTxO of {@code ada} ADA at the wallet's address (the wallet then holds two or more). */
    public static Utxo secondUtxo(DevnetKit kit, Wallet wallet, long ada) {
        return kit.topup(wallet.address(), ada);
    }

    /**
     * A wallet whose address is the ENTERPRISE address of a fresh key (no stake part), funded with one UTxO
     * of {@code ada} ADA. {@link Wallet#address()} reads {@code baseAddress()}, so the account answers with
     * its enterprise address there: everything built from this wallet pays from and is witnessed for it.
     */
    public static Wallet enterpriseWallet(DevnetKit kit, long ada) {
        Account account = new Account(Networks.testnet()) {
            @Override
            public String baseAddress() {
                return enterpriseAddress();
            }
        };
        Wallet wallet = new Wallet(account);
        kit.topup(wallet.address(), ada);
        return wallet;
    }

    /** Mints a fresh token to the wallet under its own key; returns the canonical id {@code <policy>.<nameHex>}. */
    public static String mintedToken(DevnetKit kit, Wallet owner) {
        return kit.mintToken(owner, "shape", 1_000);
    }

    /**
     * Registers the wallet's stake address on the devnet (a stake-key-signed certificate) and returns its
     * bech32 reward address. The wallet's UTxOs change: build the payment AFTER calling this.
     */
    public static String registeredStake(DevnetKit kit, Wallet wallet) {
        Tx registration = new Tx().registerStakeAddress(wallet.account().stakeAddress()).from(wallet.address());
        Transaction signed = new QuickTxBuilder(kit.getBackend()).compose(registration)
                .withSigner(SignerProviders.signerFrom(wallet.account())
                        .andThen(SignerProviders.stakeKeySignerFrom(wallet.account())))
                .buildAndSign();
        String hash = kit.submitAndAwait(signed);
        // The block is in; the change output being queryable is what the next payment build selects from.
        Poll.until("change of stake registration " + hash + " visible at " + wallet.address(), CHAIN_DEADLINE,
                () -> kit.utxos(wallet.address()).stream().anyMatch(u -> u.getTxHash().equals(hash))
                        ? Optional.of(true) : Optional.empty());
        return wallet.account().stakeAddress();
    }

    /** A native "sig" script of the wallet's own payment key: a policy it can mint under and witness. */
    public static NativeScript nativePolicy(Wallet wallet) {
        return DevnetKit.call(() -> ScriptPubkey.create(VerificationKey.create(wallet.account().publicKeyBytes())));
    }

    /** An existing, unspent UTxO at a throwaway address, to name as a reference input (never spent here). */
    public static Utxo referenceUtxo(DevnetKit kit) {
        return kit.topup(new Account(Networks.testnet()).baseAddress(), REFERENCE_UTXO_ADA);
    }
}
