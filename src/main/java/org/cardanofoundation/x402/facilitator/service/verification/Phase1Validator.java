package org.cardanofoundation.x402.facilitator.service.verification;

import org.cardanofoundation.x402.facilitator.model.chain.ProtocolParams;
import org.cardanofoundation.x402.facilitator.model.chain.UtxoState;
import org.cardanofoundation.x402.facilitator.model.verification.DecodedTransaction;
import java.util.Map;
import java.util.Optional;

/** Complete ledger validation extension. Must validate the exact raw transaction, including script execution. */
@FunctionalInterface
public interface Phase1Validator {
    /** Empty means completely validated; a message rejects the transaction. Exceptions fail closed. */
    Optional<String> check(DecodedTransaction transaction, Map<String, UtxoState> inputs,
                           ProtocolParams parameters, String network);
}
