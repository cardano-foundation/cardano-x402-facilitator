package org.cardanofoundation.x402.facilitator.service.verification.method;

import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.model.verification.DecodedTransaction;

import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;

/** Address-to-address needs nothing beyond the shared checks. */
public class DefaultTransferVerifier implements TransferMethodVerifier {

    @Override
    public boolean supports(String method) {
        return method == null || method.equals("default");
    }

    @Override
    public Optional<String> check(Map<String, Object> extra, PaymentRequirements requirements,
                                  DecodedTransaction tx, String payer, BigInteger coinsPerUtxoByte) {
        return Optional.empty();
    }
}
