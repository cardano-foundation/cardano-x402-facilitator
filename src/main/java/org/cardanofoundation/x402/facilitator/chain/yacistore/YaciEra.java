package org.cardanofoundation.x402.facilitator.chain.yacistore;

import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.model.Era;

/**
 * Maps the indexer's current ledger {@link Era} to the {@link TxBodyType} that N2N/N2C
 * transaction submission must wire (spec section 9.3 / audit — yaci's convenience
 * {@code submitTxBytes(byte[])} overload hardcodes {@code BABBAGE}, which mis-tags a
 * Conway tx; this facilitator always submits with the era-correct body type instead).
 */
public final class YaciEra {

    public static TxBodyType txBodyType(Era era) {
        if (era == null) return TxBodyType.CONWAY;
        return switch (era) {
            case Conway -> TxBodyType.CONWAY;
            case Babbage -> TxBodyType.BABBAGE;
            case Alonzo -> TxBodyType.ALONZO;
            // Pre-Alonzo eras cannot carry x402 payments; default to the current era's
            // body type so a mis-detected tip never mis-tags a modern transaction.
            default -> TxBodyType.CONWAY;
        };
    }

    private YaciEra() {
    }
}
