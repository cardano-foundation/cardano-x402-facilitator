package org.cardanofoundation.x402.facilitator.e2e.kit;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.backend.model.TransactionContent;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.VerificationKey;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The devnet, seen from the test side: funds fresh wallets through the DevKit
 * admin API, mints test tokens, and checks the chain DIRECTLY against Yano —
 * never through the shim or the facilitator — so on-chain proof is independent
 * of the system under test.
 */
@Log4j2
public final class DevnetKit {

    /** The devnet poses as preview (protocol magic 2). */
    public static final String NETWORK = "cardano:preview";

    private static final Duration CHAIN_DEADLINE = Duration.ofSeconds(120);
    private static final int UTXO_PAGE = 100;

    @Getter
    private final BackendService backend;
    private final FacilitatorClient admin;

    public DevnetKit(String chainUrl, String adminUrl) {
        this.backend = new BFBackendService(chainUrl + "/", "devnet");
        this.admin = new FacilitatorClient(adminUrl);
    }

    public static DevnetKit fromEnv() {
        return new DevnetKit(E2eEnv.chainUrl(), E2eEnv.adminUrl());
    }

    /** A fresh random wallet holding exactly one UTxO of {@code ada} ADA. */
    public Wallet newWallet(long ada) {
        Wallet wallet = new Wallet(new Account(Networks.testnet()));
        topup(wallet.address(), ada);
        return wallet;
    }

    /**
     * Sends {@code ada} ADA from the devnet faucet and waits until that exact
     * new UTxO is spendable.
     *
     * @return the new UTxO.
     */
    public Utxo topup(String address, long ada) {
        Set<String> before = new HashSet<>();
        for (Utxo utxo : utxos(address)) before.add(outRef(utxo));
        FacilitatorClient.Response response = admin.post("/local-cluster/api/addresses/topup",
                FacilitatorClient.toJson(Map.of("address", address, "adaAmount", ada)));
        if (response.status() != 200 || !response.json().path("status").asBoolean()) {
            throw new IllegalStateException("devnet topup failed: " + response);
        }
        String lovelace = BigInteger.valueOf(ada).multiply(BigInteger.valueOf(1_000_000)).toString();
        return Poll.until("topup of " + ada + " ADA to " + address, CHAIN_DEADLINE,
                () -> utxos(address).stream()
                        .filter(u -> !before.contains(outRef(u)))
                        .filter(u -> u.getAmount().size() == 1 && lovelace.equals(u.getAmount().get(0).getQuantity().toString()))
                        .findFirst());
    }

    /**
     * Every UTxO at the address (all pages). An address with no UTxOs is a 404
     * in Blockfrost semantics and reads as empty; any other failure throws.
     */
    public List<Utxo> utxos(String address) {
        List<Utxo> all = new ArrayList<>();
        for (int page = 1; ; page++) {
            int p = page;
            Result<List<Utxo>> result = call(() -> backend.getUtxoService().getUtxos(address, UTXO_PAGE, p));
            if (!result.isSuccessful()) {
                if (result.code() == 404) return all;
                throw new IllegalStateException("utxo lookup failed for " + address + ": " + result.getResponse());
            }
            List<Utxo> batch = result.getValue() == null ? List.of() : result.getValue();
            all.addAll(batch);
            if (batch.size() < UTXO_PAGE) return all;
        }
    }

    private static String outRef(Utxo utxo) {
        return utxo.getTxHash() + "#" + utxo.getOutputIndex();
    }

    public long tipSlot() {
        return call(() -> backend.getBlockService().getLatestBlock()).getValue().getSlot();
    }

    public ProtocolParams protocolParams() {
        return call(() -> backend.getEpochService().getProtocolParameters()).getValue();
    }

    public Optional<TransactionContent> transaction(String txHash) {
        Result<TransactionContent> result = call(() -> backend.getTransactionService().getTransaction(txHash));
        return result.isSuccessful() && result.getValue() != null && result.getValue().getBlock() != null
                ? Optional.of(result.getValue()) : Optional.empty();
    }

    /**
     * Independent proof that a transaction is in a block, by its LOCALLY
     * computed hash — never the facilitator's word for it.
     *
     * @return the block hash.
     */
    public String assertOnChain(String txHash) {
        return Poll.until("tx " + txHash + " on-chain", CHAIN_DEADLINE, () -> transaction(txHash))
                .getBlock();
    }

    /**
     * Whether the tx is in a block right now. NOT a proof of "never submitted":
     * a wrongly broadcast tx sits in the mempool for a block or so and reads
     * false here. Prove non-submission with {@link ShimClient#submitCount} == 0.
     */
    public boolean isOnChain(String txHash) {
        return transaction(txHash).isPresent();
    }

    /** Submits straight to Yano (bypassing shim and facilitator) and waits for inclusion. */
    public String submitAndAwait(Transaction signed) {
        byte[] bytes = serialize(signed);
        String hash = TransactionUtil.getTxHash(bytes).toLowerCase();
        Result<String> result = call(() -> backend.getTransactionService().submitTransaction(bytes));
        if (!result.isSuccessful()) {
            throw new IllegalStateException("devnet submit failed: " + result.getResponse());
        }
        assertOnChain(hash);
        return hash;
    }

    /**
     * Mints {@code quantity} of a fresh native token to the owner, under a
     * single-signature native-script policy of the owner's own key.
     * {@code assetName} is UTF-8 text (CCL would read a "0x" prefix as hex).
     *
     * @return the canonical x402 asset id {@code <policyHex>.<assetNameHex>}.
     */
    public String mintToken(Wallet owner, String assetName, long quantity) {
        ScriptPubkey policy = call(() -> ScriptPubkey.create(VerificationKey.create(owner.account().publicKeyBytes())));
        String policyId = call(policy::getPolicyId);
        Asset asset = new Asset(assetName, BigInteger.valueOf(quantity));
        Tx tx = new Tx()
                .mintAssets(policy, List.of(asset), owner.address())
                .from(owner.address());
        Transaction signed = new QuickTxBuilder(backend).compose(tx)
                .withSigner(SignerProviders.signerFrom(owner.account()))
                .buildAndSign();
        String hash = submitAndAwait(signed);
        String assetNameHex = HexUtil.encodeHexString(assetName.getBytes(StandardCharsets.UTF_8));
        String unit = policyId + assetNameHex;
        Poll.until("minted " + unit + " visible at " + owner.address(), CHAIN_DEADLINE,
                () -> utxos(owner.address()).stream()
                        .anyMatch(u -> u.getTxHash().equals(hash)
                                && u.getAmount().stream().anyMatch(a -> unit.equals(a.getUnit())))
                        ? Optional.of(true) : Optional.empty());
        log.info("minted {} x {} (tx {})", quantity, unit, hash);
        return policyId + "." + assetNameHex;
    }

    static byte[] serialize(Transaction tx) {
        return call(tx::serialize);
    }

    @FunctionalInterface
    interface Checked<T> {
        T get() throws Exception;
    }

    static <T> T call(Checked<T> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
