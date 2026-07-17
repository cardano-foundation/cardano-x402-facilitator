package org.cardanofoundation.x402.facilitator.chain.yacistore;

import org.cardanofoundation.x402.facilitator.chain.ChainLookupException;
import org.cardanofoundation.x402.facilitator.chain.ProtocolParamsProvider;
import org.cardanofoundation.x402.facilitator.model.chain.ProtocolParams;

import java.util.Optional;

/**
 * yaci-store-backed protocol-params provider: reads the latest epoch's
 * {@code coinsPerUtxoByte} ({@code ada_per_utxo_byte}) and {@code maxTxSize} from
 * the indexed epoch-param store. Decoupled from yaci-store types via a supplier
 * so the mapping is unit-testable.
 */
public class YaciStoreProtocolParamsProvider implements ProtocolParamsProvider {

    /** Latest indexed protocol params, empty until the indexer has seen an epoch boundary. */
    public interface LatestParams {
        Optional<ProtocolParams> latest();
    }

    private final LatestParams source;

    public YaciStoreProtocolParamsProvider(LatestParams source) {
        this.source = source;
    }

    @Override
    public ProtocolParams current() {
        return source.latest().orElseThrow(
                () -> new ChainLookupException("yaci-store has no indexed protocol params yet"));
    }
}
