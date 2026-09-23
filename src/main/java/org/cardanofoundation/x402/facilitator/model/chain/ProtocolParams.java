package org.cardanofoundation.x402.facilitator.model.chain;

import java.math.BigInteger;

public record ProtocolParams(BigInteger coinsPerUtxoByte, int maxTxSize,
                             BigInteger minFeeCoefficient, BigInteger minFeeConstant) {
    public ProtocolParams(BigInteger coinsPerUtxoByte, int maxTxSize) {
        this(coinsPerUtxoByte, maxTxSize, null, null);
    }
}
