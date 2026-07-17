package org.cardanofoundation.x402.facilitator.model.chain;

import java.math.BigInteger;

public record ProtocolParams(BigInteger coinsPerUtxoByte, int maxTxSize) {
}
