package org.cardanofoundation.x402.facilitator.service.verification;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressType;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.MinAdaCalculator;
import com.bloxbean.cardano.client.util.HexUtil;
import org.cardanofoundation.x402.facilitator.chain.ChainLookupException;
import org.cardanofoundation.x402.facilitator.chain.FacilitatorChainService;
import org.cardanofoundation.x402.facilitator.chain.ProtocolParamsProvider;
import org.cardanofoundation.x402.facilitator.model.ErrorCodes;
import org.cardanofoundation.x402.facilitator.model.chain.UtxoState;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentPayload;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.model.protocol.VerifyResponse;
import org.cardanofoundation.x402.facilitator.model.verification.DecodedTransaction;
import org.cardanofoundation.x402.facilitator.service.registry.CardanoNetworks;
import org.cardanofoundation.x402.facilitator.service.verification.decoder.CardanoTransactionDecoder;
import org.cardanofoundation.x402.facilitator.service.verification.method.TransferMethodVerifier;

import lombok.RequiredArgsConstructor;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The verification pipeline (Stages A-E): A5 requirement validation
 * (amount/asset/payTo network tag), C3 protocol maxTxSize, D5 payer
 * authorization, and tri-state UTxO handling.
 */
@RequiredArgsConstructor
public class ExactCardanoScheme {

    private static final Pattern NONCE_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}#\\d+$");
    private static final Pattern ASSET_PATTERN = Pattern.compile("^[0-9a-fA-F]{56}\\.[0-9a-fA-F]{0,64}$");
    private static final String SCHEME_EXACT = "exact";

    private final FacilitatorChainService chain;
    private final ProtocolParamsProvider params;
    private final CardanoTransactionDecoder decoder;
    private final List<TransferMethodVerifier> methodVerifiers;
    private final int maxTxBytes;

    public VerifyResponse verify(PaymentPayload payload, PaymentRequirements requirements) {
        try {
            // Stage A — envelope (no I/O)
            if (payload.x402Version() != 2)
                return VerifyResponse.invalid(ErrorCodes.UNSUPPORTED_VERSION, null, "");
            if (payload.accepted() == null
                    || !SCHEME_EXACT.equals(payload.accepted().scheme())
                    || !SCHEME_EXACT.equals(requirements.scheme()))
                return VerifyResponse.invalid(ErrorCodes.UNSUPPORTED_SCHEME, null, "");
            if (!Objects.equals(CardanoNetworks.normalize(payload.accepted().network()),
                    CardanoNetworks.normalize(requirements.network())))
                return VerifyResponse.invalid(ErrorCodes.NETWORK_MISMATCH, null, "");
            if (!CardanoNetworks.isSupported(requirements.network()))
                return VerifyResponse.invalid(ErrorCodes.NETWORK_MISMATCH, null, "");

            String txB64 = str(payload.payload(), "transaction");
            String nonce = str(payload.payload(), "nonce");
            if (txB64 == null || txB64.isEmpty() || nonce == null || nonce.isEmpty())
                return VerifyResponse.invalid(ErrorCodes.INVALID_PAYLOAD, null, "");
            if (!NONCE_PATTERN.matcher(nonce).matches())
                return VerifyResponse.invalid(ErrorCodes.NONCE_INVALID, null, "");
            // A4: static DoS bound on the decoded size, before any CBOR parsing.
            int approxDecoded = txB64.length() * 3 / 4;
            if (approxDecoded > maxTxBytes)
                return VerifyResponse.invalid(ErrorCodes.INVALID_PAYLOAD,
                        "transaction exceeds max-tx-bytes (" + maxTxBytes + ")", "");

            // A5: requirement validation
            BigInteger requestedAmount;
            try {
                requestedAmount = new BigInteger(requirements.amount());
            } catch (RuntimeException e) {
                return VerifyResponse.invalid(ErrorCodes.INVALID_PAYLOAD,
                        "amount is not an integer string", "");
            }
            if (requestedAmount.signum() <= 0)
                return VerifyResponse.invalid(ErrorCodes.INVALID_PAYLOAD,
                        "amount must be positive", "");
            String assetKey = requirements.asset() == null ? "" : requirements.asset().toLowerCase();
            boolean isLovelace = "lovelace".equals(assetKey);
            if (!isLovelace) {
                if (!ASSET_PATTERN.matcher(assetKey).matches()
                        || (assetKey.length() - 57) % 2 != 0)
                    return VerifyResponse.invalid(ErrorCodes.INVALID_PAYLOAD,
                            "asset must be 'lovelace' or policyIdHex.assetNameHex", "");
            }
            int expectedNetworkId = CardanoNetworks.networkId(requirements.network());
            if (!payToNetworkTagMatches(requirements.payTo(), expectedNetworkId))
                return VerifyResponse.invalid(ErrorCodes.INVALID_PAYLOAD,
                        "payTo is not a valid Shelley address for " + requirements.network(), "");

            // Stage B — decode (no I/O)
            DecodedTransaction tx;
            try {
                tx = decoder.decode(txB64);
            } catch (CardanoTransactionDecoder.TransactionDecodeException e) {
                return VerifyResponse.invalid(ErrorCodes.DECODE_FAILED, e.getMessage(), "");
            }
            if (tx.networkId() != null && tx.networkId() != expectedNetworkId)
                return VerifyResponse.invalid(ErrorCodes.NETWORK_ID_MISMATCH, null, "");
            // SECURITY: refuse unsigned txs so /verify can't green-light an unpaid request.
            if (tx.vkeyWitnessCount() == 0 && tx.scriptWitnessCount() == 0)
                return VerifyResponse.invalid(ErrorCodes.UNSIGNED, null, "");
            if (!tx.signaturesValid())
                return VerifyResponse.invalid(ErrorCodes.INVALID_SIGNATURE, null, "");

            // Stage C — time & protocol limits
            if (tx.ttlSlot() != null || tx.validityStartSlot() != null) {
                long currentSlot;
                try {
                    currentSlot = chain.getCurrentSlot();
                } catch (RuntimeException e) {
                    return VerifyResponse.invalid(ErrorCodes.CHAIN_LOOKUP_FAILED, e.getMessage(), "");
                }
                if (tx.ttlSlot() != null && tx.ttlSlot() <= currentSlot)
                    return VerifyResponse.invalid(ErrorCodes.TTL_EXPIRED, null, "");
                if (tx.validityStartSlot() != null && tx.validityStartSlot() > currentSlot)
                    return VerifyResponse.invalid(ErrorCodes.NOT_YET_VALID, null, "");
            }
            org.cardanofoundation.x402.facilitator.model.chain.ProtocolParams pp;
            try {
                pp = params.current();
            } catch (RuntimeException e) {
                return VerifyResponse.invalid(ErrorCodes.CHAIN_LOOKUP_FAILED, e.getMessage(), "");
            }
            if (tx.serializedSize() > Math.min(maxTxBytes, pp.maxTxSize()))
                return VerifyResponse.invalid(ErrorCodes.INVALID_PAYLOAD,
                        "transaction size " + tx.serializedSize() + " exceeds protocol maxTxSize "
                                + pp.maxTxSize(), "");

            // Stage D — replay protection (chain UTxO set)
            String nonceLower = normalizeNonce(nonce);
            if (!tx.inputs().contains(nonceLower))
                return VerifyResponse.invalid(ErrorCodes.NONCE_NOT_IN_INPUTS, null, "");

            Map<String, UtxoState> states = new LinkedHashMap<>();
            try {
                for (String ref : tx.inputs()) {
                    int hashEnd = ref.indexOf('#');
                    states.put(ref, chain.getUtxoState(ref.substring(0, hashEnd),
                            Integer.parseInt(ref.substring(hashEnd + 1))));
                }
            } catch (RuntimeException e) {
                return VerifyResponse.invalid(ErrorCodes.CHAIN_LOOKUP_FAILED, e.getMessage(), "");
            }
            // Unknown under the default fail policy is a retryable lookup failure —
            // never a deterministic verdict.
            if (states.values().stream().anyMatch(s -> s instanceof UtxoState.Unknown))
                return VerifyResponse.invalid(ErrorCodes.CHAIN_LOOKUP_FAILED,
                        "input state unknown (indexer sync horizon)", "");
            UtxoState nonceState = states.get(nonceLower);
            if (!(nonceState instanceof UtxoState.Unspent nonceUnspent))
                return VerifyResponse.invalid(ErrorCodes.NONCE_NOT_ON_CHAIN, null, "");
            String payer = nonceUnspent.ownerAddress();
            if (states.values().stream().anyMatch(s -> s instanceof UtxoState.Spent))
                return VerifyResponse.invalid(ErrorCodes.INPUT_NOT_AVAILABLE, null, payer);

            // D5 — payer authorization
            Optional<String> payerError = checkPayerAuthorization(payer, tx);
            if (payerError.isPresent())
                return VerifyResponse.invalid(payerError.get(), null, payer);

            // Stage E — value transfer (rules 2/3/4/7) + method dispatch
            return checkValueTransfer(tx, requirements, isLovelace, assetKey, requestedAmount, pp, payer);
        } catch (ChainLookupException e) {
            return VerifyResponse.invalid(ErrorCodes.CHAIN_LOOKUP_FAILED, e.getMessage(), "");
        } catch (Exception e) {
            return VerifyResponse.invalid(ErrorCodes.VERIFICATION_ERROR, e.getMessage(), "");
        }
    }

    /**
     * Stage E: take the first output to {@code payTo} that fully covers the amount,
     * enforce the min-UTXO floor on it, then run the per-method verifier. When no
     * output qualifies, attribute the failure by how far the scan got
     * (recipient -> asset -> amount).
     */
    private VerifyResponse checkValueTransfer(DecodedTransaction tx, PaymentRequirements requirements,
                                              boolean isLovelace, String assetKey, BigInteger requestedAmount,
                                              org.cardanofoundation.x402.facilitator.model.chain.ProtocolParams pp,
                                              String payer) {
        boolean recipientFound = false, assetFound = false;
        BigInteger bestAvailable = BigInteger.ZERO;

        for (DecodedTransaction.Output out : tx.outputs()) {
            if (!out.address().equals(requirements.payTo())) continue;
            recipientFound = true;
            BigInteger available = isLovelace ? out.coin() : out.assets().get(assetKey);
            if (available == null) continue;
            assetFound = true;
            if (available.compareTo(bestAvailable) > 0) bestAvailable = available;
            if (available.compareTo(requestedAmount) >= 0) {
                ProtocolParams cclParams = new ProtocolParams();
                cclParams.setCoinsPerUtxoSize(pp.coinsPerUtxoByte().toString());
                BigInteger minUtxo = new MinAdaCalculator(cclParams).calculateMinAda(out.raw());
                if (out.coin().compareTo(minUtxo) < 0)
                    return VerifyResponse.invalid(ErrorCodes.MIN_UTXO_INSUFFICIENT,
                            "output to " + requirements.payTo() + " carries " + out.coin()
                                    + " lovelace, min-UTXO requires " + minUtxo, payer);

                // Method checks read CANONICAL requirements.extra (never accepted.extra).
                String method = requirements.extra() == null ? "default"
                        : String.valueOf(requirements.extra().getOrDefault("assetTransferMethod", "default"));
                Optional<TransferMethodVerifier> verifier = methodVerifiers.stream()
                        .filter(v -> v.supports(method)).findFirst();
                if (verifier.isEmpty())
                    return VerifyResponse.invalid(ErrorCodes.UNSUPPORTED_SCHEME,
                            "assetTransferMethod '" + method + "' is not supported by this facilitator", payer);
                Optional<String> methodError = verifier.get()
                        .check(requirements.extra(), requirements, tx, payer, pp.coinsPerUtxoByte());
                if (methodError.isPresent())
                    return VerifyResponse.invalid(methodError.get(), null, payer);

                return VerifyResponse.valid(payer);
            }
        }

        if (!recipientFound) return VerifyResponse.invalid(ErrorCodes.RECIPIENT_MISMATCH, null, payer);
        if (!assetFound) return VerifyResponse.invalid(ErrorCodes.ASSET_MISMATCH, null, payer);
        return VerifyResponse.invalid(ErrorCodes.AMOUNT_INSUFFICIENT,
                "output to " + requirements.payTo() + " pays " + bestAvailable
                        + ", requires " + requestedAmount, payer);
    }

    /**
     * Replay-safe profile: the I/O-free mandatory checks only — Stages A+B
     * plus D1 and D5 against the journaled payer. Used by the settlement
     * service's opt-in idempotent replay; never touches the chain.
     */
    public VerifyResponse verifyReplayProfile(PaymentPayload payload, PaymentRequirements requirements,
                                              String journaledNonce, String journaledPayer) {
        try {
            if (payload.x402Version() != 2)
                return VerifyResponse.invalid(ErrorCodes.UNSUPPORTED_VERSION, null, "");
            if (payload.accepted() == null
                    || !SCHEME_EXACT.equals(payload.accepted().scheme())
                    || !SCHEME_EXACT.equals(requirements.scheme()))
                return VerifyResponse.invalid(ErrorCodes.UNSUPPORTED_SCHEME, null, "");
            if (!Objects.equals(CardanoNetworks.normalize(payload.accepted().network()),
                    CardanoNetworks.normalize(requirements.network()))
                    || !CardanoNetworks.isSupported(requirements.network()))
                return VerifyResponse.invalid(ErrorCodes.NETWORK_MISMATCH, null, "");
            String txB64 = str(payload.payload(), "transaction");
            String nonce = str(payload.payload(), "nonce");
            if (txB64 == null || nonce == null || !NONCE_PATTERN.matcher(nonce).matches())
                return VerifyResponse.invalid(ErrorCodes.INVALID_PAYLOAD, null, "");
            DecodedTransaction tx;
            try {
                tx = decoder.decode(txB64);
            } catch (CardanoTransactionDecoder.TransactionDecodeException e) {
                return VerifyResponse.invalid(ErrorCodes.DECODE_FAILED, e.getMessage(), "");
            }
            if (tx.vkeyWitnessCount() == 0 && tx.scriptWitnessCount() == 0)
                return VerifyResponse.invalid(ErrorCodes.UNSIGNED, null, "");
            if (!tx.signaturesValid())
                return VerifyResponse.invalid(ErrorCodes.INVALID_SIGNATURE, null, "");
            String nonceLower = normalizeNonce(nonce);
            if (!tx.inputs().contains(nonceLower) || !nonceLower.equals(journaledNonce))
                return VerifyResponse.invalid(ErrorCodes.NONCE_NOT_IN_INPUTS, null, "");
            Optional<String> payerError = checkPayerAuthorization(journaledPayer, tx);
            if (payerError.isPresent())
                return VerifyResponse.invalid(payerError.get(), null, journaledPayer);
            return VerifyResponse.valid(journaledPayer);
        } catch (Exception e) {
            return VerifyResponse.invalid(ErrorCodes.VERIFICATION_ERROR, e.getMessage(), "");
        }
    }

    /**
     * D5: key-credential payer must be among the verified vkey witnesses;
     * script-credential payer needs at least one script witness; Byron payers
     * are rejected (bootstrap witnesses are never cryptographically verified).
     */
    private static Optional<String> checkPayerAuthorization(String payer, DecodedTransaction tx) {
        Address address;
        try {
            address = new Address(payer);
        } catch (RuntimeException e) {
            // Not a Shelley bech32 address (Byron/base58 or garbage) — unsupported payer.
            return Optional.of(ErrorCodes.PAYER_NOT_WITNESS);
        }
        if (address.getAddressType() == AddressType.Byron)
            return Optional.of(ErrorCodes.PAYER_NOT_WITNESS);
        Optional<Credential> credential = address.getPaymentCredential();
        if (credential.isEmpty())
            return Optional.of(ErrorCodes.PAYER_NOT_WITNESS);
        return switch (credential.get().getType()) {
            case Key -> {
                String keyHash = HexUtil.encodeHexString(credential.get().getBytes()).toLowerCase();
                yield tx.verifiedWitnessKeyHashes().contains(keyHash)
                        ? Optional.empty()
                        : Optional.of(ErrorCodes.PAYER_NOT_WITNESS);
            }
            case Script -> tx.scriptWitnessCount() >= 1
                    ? Optional.empty()
                    : Optional.of(ErrorCodes.PAYER_NOT_WITNESS);
        };
    }

    private static boolean payToNetworkTagMatches(String payTo, int expectedNetworkId) {
        if (payTo == null) return false;
        try {
            Address address = new Address(payTo);
            if (address.getAddressType() == AddressType.Byron) return false;
            String prefix = address.getPrefix();
            return expectedNetworkId == 1 ? "addr".equals(prefix) : "addr_test".equals(prefix);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Canonical "{@code <txHashLower>#<index>}" nonce: lowercase the hash, reparse the index. */
    private static String normalizeNonce(String nonce) {
        int sep = nonce.indexOf('#');
        return nonce.substring(0, sep).toLowerCase() + "#" + Integer.parseInt(nonce.substring(sep + 1));
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        return v instanceof String s ? s : null;
    }
}
