package org.cardanofoundation.x402.facilitator.e2e.kit;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import lombok.extern.log4j.Log4j2;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The suite's process-wide wallet pool. {@link #wallet(long)} hands out a fresh
 * wallet holding exactly one UTxO of the requested ADA; the pool refills under a
 * lock with ONE faucet top-up to a bank wallet, ONE fan-out transaction from that
 * bank to {@link #REFILL} fresh accounts, and one wait for inclusion. That is a
 * handful of blocks per refill instead of one faucet round trip per wallet, and
 * it is safe for classes running in parallel.
 *
 * <p>The refill is chunked at {@link #MAX_OUTPUTS_PER_TX} outputs per transaction
 * (the measured limits are in the WP-11 Log); a refill larger than one chunk
 * spends the bank's change in a follow-up transaction after the previous one is
 * in a block.
 */
@Log4j2
public final class Funding {

    /** Fresh wallets per refill of one amount. */
    static final int REFILL = 30;
    /** Outputs per fan-out transaction; well below the protocol's max_tx_size (see the WP-11 Log). */
    static final int MAX_OUTPUTS_PER_TX = 100;
    /** ADA the bank keeps beyond the fan-out, for the fee and its own change output. */
    private static final long BANK_HEADROOM_ADA = 10;
    private static final Duration CHAIN_DEADLINE = Duration.ofSeconds(120);

    private static final Object LOCK = new Object();
    private static final Map<Long, Deque<Wallet>> POOL = new HashMap<>();
    private static DevnetKit kit;
    private static volatile String largestFanOut = "no fan-out yet";
    private static int largestFanOutBytes;

    private Funding() {
    }

    /** A fresh random wallet holding exactly one UTxO of {@code ada} ADA. */
    public static Wallet wallet(long ada) {
        synchronized (LOCK) {
            Deque<Wallet> pool = POOL.computeIfAbsent(ada, a -> new ArrayDeque<>());
            if (pool.isEmpty()) {
                pool.addAll(refill(ada));
            }
            return pool.pop();
        }
    }

    /**
     * {@code n} fresh wallets of exactly one UTxO of {@code ada} ADA each, in one go: pooled wallets
     * of that amount first, the rest from ONE faucet top-up and as many chunked fan-out transactions
     * as needed ({@link #MAX_OUTPUTS_PER_TX} outputs each). Several hundred wallets cost a handful of
     * blocks, not one 30-wallet refill after another.
     */
    public static List<Wallet> wallets(int n, long ada) {
        List<Wallet> out = new ArrayList<>(Math.max(n, 0));
        if (n <= 0) return out;
        synchronized (LOCK) {
            Deque<Wallet> pool = POOL.computeIfAbsent(ada, a -> new ArrayDeque<>());
            while (out.size() < n && !pool.isEmpty()) out.add(pool.pop());
            if (out.size() < n) out.addAll(refill(ada, n - out.size()));
        }
        return out;
    }

    /** The largest fan-out transaction built so far: outputs, serialized bytes and the protocol's limit. */
    public static String largestFanOut() {
        return largestFanOut;
    }

    private static DevnetKit kit() {
        if (kit == null) kit = DevnetKit.fromEnv();
        return kit;
    }

    /** For the log only: a failed parameters read must never fail the funding of every class. */
    private static Object maxTxSize(DevnetKit devnet) {
        try {
            Object value = devnet.protocolParams().getMaxTxSize();
            return value == null ? "?" : value;
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private static List<Wallet> refill(long ada) {
        return refill(ada, REFILL);
    }

    private static List<Wallet> refill(long ada, int count) {
        DevnetKit devnet = kit();
        long started = System.nanoTime();
        Wallet bank = new Wallet(new Account(Networks.testnet()));
        List<Wallet> fresh = new ArrayList<>(count);
        for (int i = 0; i < count; i++) fresh.add(new Wallet(new Account(Networks.testnet())));

        // One top-up covers every chunk; the headroom grows with the number of fan-out fees.
        long chunks = (count + MAX_OUTPUTS_PER_TX - 1) / MAX_OUTPUTS_PER_TX;
        devnet.topup(bank.address(), ada * count + BANK_HEADROOM_ADA * chunks);
        for (int from = 0; from < fresh.size(); from += MAX_OUTPUTS_PER_TX) {
            List<Wallet> chunk = fresh.subList(from, Math.min(fresh.size(), from + MAX_OUTPUTS_PER_TX));
            Tx tx = new Tx().from(bank.address());
            for (Wallet wallet : chunk) tx.payToAddress(wallet.address(), Amount.ada(ada));
            Transaction signed = new QuickTxBuilder(devnet.getBackend()).compose(tx)
                    .withSigner(SignerProviders.signerFrom(bank.account()))
                    .buildAndSign();
            int bytes = DevnetKit.serialize(signed).length;
            Object maxTxSize = maxTxSize(devnet);
            log.info("funding: fan-out tx of {} outputs is {} bytes (max_tx_size {})", chunk.size(), bytes, maxTxSize);
            if (bytes > largestFanOutBytes) {
                largestFanOutBytes = bytes;
                largestFanOut = chunk.size() + " outputs = " + bytes + " bytes (max_tx_size " + maxTxSize + ")";
            }
            devnet.submitAndAwait(signed);
            // The next chunk spends this one's change: wait until its last output is queryable.
            Wallet lastOfChunk = chunk.get(chunk.size() - 1);
            Poll.until("fan-out output visible at " + lastOfChunk.address(), CHAIN_DEADLINE,
                    () -> devnet.utxos(lastOfChunk.address()).isEmpty() ? Optional.empty() : Optional.of(true));
        }
        log.info("funding: {} wallets of {} ADA ready in {} ms", count, ada, (System.nanoTime() - started) / 1_000_000);
        return fresh;
    }
}
