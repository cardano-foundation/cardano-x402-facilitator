package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import java.math.BigInteger;

/**
 * Masumi {@code vested_pay} (payment-v2 / {@code Web3CardanoV2}) constants and
 * the post-result min-UTXO estimator. The estimator mirrors Masumi's own
 * {@code calculateMinUtxo}, sizing the escrow output for the datum as it will
 * look AFTER {@code SubmitResult} (32-byte {@code result_hash}, non-zero
 * cooldowns) so a lock this facilitator accepts also clears Masumi's off-chain
 * check and the seller can actually spend it.
 */
public final class MasumiConstants {

    /**
     * Non-zero {@code collateral_return_lovelace} floor enforced by Masumi's
     * off-chain validation ({@code CONSTANTS.MIN_COLLATERAL_LOVELACE}). A
     * positive collateral below this is rejected.
     */
    public static final BigInteger MASUMI_MIN_COLLATERAL_LOVELACE = BigInteger.valueOf(1_435_230L);

    // Byte deltas / buffers used by the min-UTXO estimator below.
    /** CBOR byte delta of an empty vs 32-byte {@code result_hash} (0x40 -> 0x5820…). */
    private static final int RESULT_HASH_DELTA_BYTES = 33;
    /** Constant overhead (input + UTXO-map entry), same as the ledger's 160. */
    private static final int MINUTXO_OVERHEAD_BYTES = 160;
    /** Headroom buffer for the submitted result hash. */
    private static final int MINUTXO_RESULT_HASH_BUFFER = 50;
    /** Headroom buffer for the two cooldown timestamps becoming non-zero. */
    private static final int MINUTXO_COOLDOWN_BUFFER = 15;
    /** Safety margin keeping the estimate above the ledger floor. */
    private static final int MINUTXO_SAFETY_MARGIN = 100;
    /** Per-native-token headroom buffer. */
    private static final int MINUTXO_PER_TOKEN_BUFFER = 50;

    /**
     * Minimum lovelace the escrow output must carry, computed on the datum as it
     * will look after {@code SubmitResult}.
     *
     * @param lockDatumBytes   byte length of the current (empty-result) lock datum
     * @param nativeTokenCount distinct native tokens on the escrow output
     * @param coinsPerUtxoByte live {@code coinsPerUtxoByte} protocol parameter
     * @return the minimum lovelace the escrow output must hold
     */
    public static BigInteger masumiMinUtxoLovelace(int lockDatumBytes, int nativeTokenCount,
                                                   BigInteger coinsPerUtxoByte) {
        long totalBytes = (long) lockDatumBytes
                + RESULT_HASH_DELTA_BYTES
                + MINUTXO_OVERHEAD_BYTES
                + MINUTXO_RESULT_HASH_BUFFER
                + MINUTXO_COOLDOWN_BUFFER
                + MINUTXO_SAFETY_MARGIN
                + (long) MINUTXO_PER_TOKEN_BUFFER * nativeTokenCount;
        return coinsPerUtxoByte.multiply(BigInteger.valueOf(totalBytes));
    }

    private MasumiConstants() {
    }
}
