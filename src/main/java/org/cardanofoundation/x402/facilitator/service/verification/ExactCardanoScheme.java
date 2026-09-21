package org.cardanofoundation.x402.facilitator.service.verification;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressType;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.MinAdaCalculator;
import com.bloxbean.cardano.client.util.HexUtil;
import org.cardanofoundation.x402.facilitator.chain.ChainLookupException;
import org.cardanofoundation.x402.facilitator.chain.FacilitatorChainService;
import org.cardanofoundation.x402.facilitator.chain.NetworkClock;
import org.cardanofoundation.x402.facilitator.chain.ProtocolParamsProvider;
import org.cardanofoundation.x402.facilitator.model.ErrorCodes;
import org.cardanofoundation.x402.facilitator.model.chain.ProtocolParams;
import org.cardanofoundation.x402.facilitator.model.chain.UtxoState;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentPayload;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.model.protocol.VerifyResponse;
import org.cardanofoundation.x402.facilitator.model.verification.DecodedTransaction;
import org.cardanofoundation.x402.facilitator.service.registry.CardanoNetworks;
import org.cardanofoundation.x402.facilitator.service.verification.decoder.CardanoTransactionDecoder;
import org.cardanofoundation.x402.facilitator.service.verification.method.TransferMethodVerifier;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The verification pipeline (Stages A-E): A5 requirement validation
 * (amount/asset/payTo network tag), C3 protocol maxTxSize, D5 payer
 * authorization, and tri-state UTxO handling.
 */
public class ExactCardanoScheme {

    private static final Pattern NONCE_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}#\\d+$");
    private static final Pattern ASSET_PATTERN = Pattern.compile("^[0-9a-f]{56}\\.[0-9a-f]{0,64}$");
    private static final String SCHEME_EXACT = "exact";

    private final FacilitatorChainService chain;
    private final ProtocolParamsProvider params;
    private final CardanoTransactionDecoder decoder;
    private final List<TransferMethodVerifier> methodVerifiers;
    private final int maxTxBytes;
    /** Era-aware slot<->wall-clock conversion for the rule 7 TTL bounds. */
    private final NetworkClock clock;

    private final Phase1Validator phase1Validator;

    public ExactCardanoScheme(FacilitatorChainService chain, ProtocolParamsProvider params,
                              CardanoTransactionDecoder decoder, List<TransferMethodVerifier> methodVerifiers,
                              int maxTxBytes, NetworkClock clock) {
        this(chain, params, decoder, methodVerifiers, maxTxBytes, clock, null);
    }

    public ExactCardanoScheme(FacilitatorChainService chain, ProtocolParamsProvider params,
                              CardanoTransactionDecoder decoder, List<TransferMethodVerifier> methodVerifiers,
                              int maxTxBytes, NetworkClock clock, Phase1Validator phase1Validator) {
        this.chain = chain; this.params = params; this.decoder = decoder;
        this.methodVerifiers = List.copyOf(methodVerifiers); this.maxTxBytes = Math.min(maxTxBytes, 65536);
        this.clock = clock; this.phase1Validator = phase1Validator;
    }

    public VerifyResponse verify(PaymentPayload payload, PaymentRequirements requirements) {
        return verifyInternal(payload, requirements, false, null);
    }

    /** Only callers with durable submission provenance or authenticated ledger evidence may use this. */
    public VerifyResponse verifyBroadcast(PaymentPayload payload, PaymentRequirements requirements,
                                         String journaledPayer) {
        return verifyInternal(payload, requirements, true, journaledPayer);
    }

    private VerifyResponse verifyInternal(PaymentPayload payload, PaymentRequirements requirements,
                                          boolean broadcast, String journaledPayer) {
        try {
            if (payload == null || requirements == null)
                return VerifyResponse.invalid(ErrorCodes.INVALID_PAYLOAD, null, "");
            if (payload.x402Version() != 2)
                return VerifyResponse.invalid(ErrorCodes.UNSUPPORTED_VERSION, null, "");
            if (payload.accepted() == null || !SCHEME_EXACT.equals(payload.accepted().scheme())
                    || !SCHEME_EXACT.equals(requirements.scheme()))
                return VerifyResponse.invalid(ErrorCodes.UNSUPPORTED_SCHEME, null, "");
            if (!CardanoNetworks.isSupported(requirements.network())
                    || !Objects.equals(CardanoNetworks.normalize(payload.accepted().network()),
                    CardanoNetworks.normalize(requirements.network())))
                return VerifyResponse.invalid(ErrorCodes.NETWORK_MISMATCH, null, "");
            String txB64 = str(payload.payload(), "transaction");
            String nonce = str(payload.payload(), "nonce");
            if (txB64 == null || txB64.isEmpty() || nonce == null || nonce.isEmpty())
                return VerifyResponse.invalid(ErrorCodes.INVALID_PAYLOAD, null, "");
            String nonceLower;
            try {
                if (nonce.length() > 75 || !NONCE_PATTERN.matcher(nonce).matches())
                    throw new IllegalArgumentException("invalid nonce");
                nonceLower = normalizeNonce(nonce);
            } catch (RuntimeException e) { return VerifyResponse.invalid(ErrorCodes.NONCE_INVALID, null, ""); }
            if ((long) txB64.length() > ((long) maxTxBytes + 2) / 3 * 4)
                return VerifyResponse.invalid(ErrorCodes.INVALID_PAYLOAD, "transaction exceeds max-tx-bytes", "");
            if (requirements.amount() == null || requirements.amount().length() > 80
                    || !requirements.amount().matches("[1-9][0-9]*"))
                return VerifyResponse.invalid(ErrorCodes.REQUIREMENTS_INVALID, "amount must be a canonical positive integer", "");
            BigInteger requestedAmount = new BigInteger(requirements.amount());
            String assetKey = requirements.asset() == null ? "" : requirements.asset();
            boolean isLovelace = "lovelace".equals(assetKey);
            if (!isLovelace && (!ASSET_PATTERN.matcher(assetKey).matches() || (assetKey.length() - 57) % 2 != 0))
                return VerifyResponse.invalid(ErrorCodes.REQUIREMENTS_INVALID, "invalid canonical asset", "");
            int expectedNetworkId = CardanoNetworks.networkId(requirements.network());
            if (!payToNetworkTagMatches(requirements.payTo(), expectedNetworkId))
                return VerifyResponse.invalid(ErrorCodes.INVALID_PAYLOAD, "invalid payTo network", "");
            if (CardanoPolicies.l1Confirmations(requirements.extra()) == null)
                return VerifyResponse.invalid(ErrorCodes.REQUIREMENTS_POLICY, null, "");

            DecodedTransaction tx;
            try { tx = decoder.decode(txB64); }
            catch (CardanoTransactionDecoder.TransactionDecodeException e) {
                return VerifyResponse.invalid(ErrorCodes.DECODE_FAILED, e.getMessage(), "");
            }
            if (tx.inputs().isEmpty() || tx.inputs().size() > 256 || new HashSet<>(tx.inputs()).size() != tx.inputs().size())
                return VerifyResponse.invalid(ErrorCodes.PHASE1_INVALID, "empty, duplicate, or excessive inputs", "");
            if (tx.networkId() != null && tx.networkId() != expectedNetworkId)
                return VerifyResponse.invalid(ErrorCodes.NETWORK_ID_MISMATCH, null, "");
            if (tx.vkeyWitnessCount() == 0 && tx.scriptWitnessCount() == 0)
                return VerifyResponse.invalid(ErrorCodes.UNSIGNED, null, "");
            if (!tx.signaturesValid())
                return VerifyResponse.invalid(ErrorCodes.INVALID_SIGNATURE, null, "");
            if (!tx.inputs().contains(nonceLower))
                return VerifyResponse.invalid(ErrorCodes.NONCE_NOT_IN_INPUTS, null, "");
            if (!tx.isValid())
                return VerifyResponse.invalid(ErrorCodes.PHASE2_INVALID, null, "");

            if (!broadcast && (tx.ttlSlot() != null || tx.validityStartSlot() != null)) {
                long slot = chain.getCurrentSlot();
                if (tx.ttlSlot() != null && tx.ttlSlot() <= slot)
                    return VerifyResponse.invalid(ErrorCodes.TTL_EXPIRED, null, "");
                if (tx.ttlSlot() != null && requirements.maxTimeoutSeconds() != null
                        && clock.slotToTime(tx.ttlSlot()).isAfter(clock.slotToTime(slot)
                        .plusSeconds(requirements.maxTimeoutSeconds())))
                    return VerifyResponse.invalid(ErrorCodes.TTL_TOO_FAR,
                            "ttlSlot=" + tx.ttlSlot() + ", currentSlot=" + slot
                                    + ", maxTimeoutSeconds=" + requirements.maxTimeoutSeconds(), "");
                if (tx.validityStartSlot() != null && tx.validityStartSlot() > slot)
                    return VerifyResponse.invalid(ErrorCodes.NOT_YET_VALID, null, "");
            }
            ProtocolParams pp = params.current();
            if (tx.serializedSize() > Math.min(maxTxBytes, pp.maxTxSize()))
                return VerifyResponse.invalid(ErrorCodes.INVALID_PAYLOAD, "transaction exceeds maxTxSize", "");
            Map<String, UtxoState> states = new LinkedHashMap<>();
            String payer = null;
            if (broadcast) {
                try {
                    UtxoState state = lookup(nonceLower);
                    payer = state instanceof UtxoState.Unspent u ? u.ownerAddress()
                            : state instanceof UtxoState.Spent spent ? spent.ownerAddress() : null;
                } catch (RuntimeException ignored) { /* durable payer is authenticated by the journal */ }
                if (payer == null || payer.isEmpty()) payer = journaledPayer;
                if (payer == null || payer.isEmpty())
                    return VerifyResponse.invalid(ErrorCodes.NONCE_NOT_ON_CHAIN, "nonce owner unavailable", "");
                if (journaledPayer != null && !journaledPayer.isEmpty() && !journaledPayer.equals(payer))
                    return VerifyResponse.invalid(ErrorCodes.PAYER_NOT_WITNESS, "journaled payer differs from input owner", payer);
            } else {
                for (String ref : tx.inputs()) states.put(ref, lookup(ref));
                if (states.values().stream().anyMatch(v -> v == null || v instanceof UtxoState.Unknown))
                    return VerifyResponse.invalid(ErrorCodes.CHAIN_LOOKUP_FAILED, "input state unknown", "");
                if (!(states.get(nonceLower) instanceof UtxoState.Unspent unspent))
                    return VerifyResponse.invalid(ErrorCodes.NONCE_NOT_ON_CHAIN, null, "");
                payer = unspent.ownerAddress();
                if (states.values().stream().anyMatch(v -> !(v instanceof UtxoState.Unspent)))
                    return VerifyResponse.invalid(ErrorCodes.INPUT_NOT_AVAILABLE, null, payer);
            }
            Optional<String> payerError = checkPayerAuthorization(payer, tx);
            if (payerError.isPresent()) return VerifyResponse.invalid(payerError.get(), null, payer);
            if (!broadcast) {
                Optional<String> phase1 = checkPhase1(tx, states, pp, requirements.network());
                if (phase1.isPresent()) return VerifyResponse.invalid(phase1.get(), null, payer);
            }
            return checkValueTransfer(tx, requirements, isLovelace, assetKey, requestedAmount, pp, payer, payload.resource());
        } catch (ChainLookupException e) {
            return VerifyResponse.invalid(ErrorCodes.CHAIN_LOOKUP_FAILED, e.getMessage(), "");
        } catch (Exception e) {
            return VerifyResponse.invalid(ErrorCodes.VERIFICATION_ERROR, e.getMessage(), "");
        }
    }

    private UtxoState lookup(String ref) {
        int hashEnd = ref.indexOf('#');
        try { return chain.getUtxoState(ref.substring(0, hashEnd), Integer.parseInt(ref.substring(hashEnd + 1))); }
        catch (RuntimeException e) { throw new ChainLookupException("input lookup failed", e); }
    }

    private Optional<String> checkPhase1(DecodedTransaction tx, Map<String, UtxoState> states,
                                         ProtocolParams pp, String network) {
        Set<Long> plainKeys = Set.of(0L, 1L, 2L, 3L, 7L, 8L, 14L, 15L);
        boolean plain = plainKeys.containsAll(tx.bodyKeys()) && tx.scriptWitnessCount() == 0;
        if (!tx.verifiedWitnessKeyHashes().containsAll(tx.requiredSignerKeyHashes()))
            return Optional.of(ErrorCodes.PHASE1_INVALID);
        for (UtxoState state : states.values()) {
            var input = (UtxoState.Unspent) state;
            try {
                var credential = new Address(input.ownerAddress()).getPaymentCredential().orElseThrow();
                if (credential.getType() == com.bloxbean.cardano.client.address.CredentialType.Script) plain = false;
                else if (checkPayerAuthorization(input.ownerAddress(), tx).isPresent())
                    return Optional.of(ErrorCodes.PHASE1_INVALID);
            } catch (RuntimeException e) { return Optional.of(ErrorCodes.PHASE1_INVALID); }
        }
        if (!plain && phase1Validator == null) return Optional.of(ErrorCodes.PHASE1_INVALID);
        if (tx.fee() == null || tx.fee().signum() < 0) return Optional.of(ErrorCodes.PHASE1_INVALID);
        if (plain) {
            BigInteger inputCoin = BigInteger.ZERO, outputCoin = tx.fee();
            Map<String, BigInteger> balances = new HashMap<>();
            for (UtxoState state : states.values()) {
                var input = (UtxoState.Unspent) state;
                if (input.coin() == null) return Optional.of(ErrorCodes.INPUT_VALUE_UNAVAILABLE);
                if (input.coin().signum() < 0 || input.assets().values().stream().anyMatch(v -> v.signum() < 0))
                    return Optional.of(ErrorCodes.INPUT_VALUE_UNAVAILABLE);
                inputCoin = inputCoin.add(input.coin());
                input.assets().forEach((k,v) -> balances.merge(k.toLowerCase(java.util.Locale.ROOT), v, BigInteger::add));
            }
            for (DecodedTransaction.Output output : tx.outputs()) {
                if (output.coin() == null || output.coin().signum() < 0
                        || output.assets().values().stream().anyMatch(v -> v.signum() < 0))
                    return Optional.of(ErrorCodes.PHASE1_INVALID);
                outputCoin = outputCoin.add(output.coin());
                output.assets().forEach((k,v) -> balances.merge(k, v.negate(), BigInteger::add));
            }
            if (!inputCoin.equals(outputCoin) || balances.values().stream().anyMatch(v -> v.signum() != 0))
                return Optional.of(ErrorCodes.VALUE_NOT_CONSERVED);
        }
        if (pp.minFeeCoefficient() == null || pp.minFeeConstant() == null
                || pp.minFeeCoefficient().signum() < 0 || pp.minFeeConstant().signum() < 0)
            return Optional.of(ErrorCodes.CHAIN_LOOKUP_FAILED);
        if (tx.fee().compareTo(pp.minFeeConstant().add(pp.minFeeCoefficient().multiply(BigInteger.valueOf(tx.serializedSize())))) < 0)
            return Optional.of(ErrorCodes.FEE_BELOW_MINIMUM);
        for (DecodedTransaction.Output output : tx.outputs()) {
            if (!payToNetworkTagMatches(output.address(), CardanoNetworks.networkId(network)))
                return Optional.of(ErrorCodes.NETWORK_ID_MISMATCH);
            if (output.coin().compareTo(minUtxoLovelace(output, pp)) < 0)
                return Optional.of(ErrorCodes.MIN_UTXO_INSUFFICIENT);
        }
        if (phase1Validator != null) {
            try {
                if (phase1Validator.check(tx, Map.copyOf(states), pp, network).isPresent())
                    return Optional.of(ErrorCodes.PHASE1_INVALID);
            } catch (RuntimeException e) { return Optional.of(ErrorCodes.PHASE1_INVALID); }
        }
        return Optional.empty();
    }

    /**
     * Stage E: take the first output to {@code payTo} that fully covers the amount,
     * enforce the min-UTXO floor on it, then run the per-method verifier. When no
     * output qualifies, attribute the failure by how far the scan got
     * (recipient -> asset -> amount).
     */
    private VerifyResponse checkValueTransfer(DecodedTransaction tx, PaymentRequirements requirements,
                                              boolean isLovelace, String assetKey, BigInteger requestedAmount,
                                              ProtocolParams pp,
                                              String payer, Map<String, Object> resource) {
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
                BigInteger minUtxo = minUtxoLovelace(out, pp);
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
                        .check(requirements.extra(), requirements, tx, payer, pp.coinsPerUtxoByte(), resource);
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
     * The minimum lovelace an output must carry, per the live protocol
     * parameters. Isolated because cardano-client-lib's calculator takes its own
     * {@code ProtocolParams} type, which collides by simple name with ours.
     *
     * @param output the output being priced.
     * @param pp the facilitator's view of the protocol parameters.
     * @return the min-UTXO floor in lovelace.
     */
    private static BigInteger minUtxoLovelace(DecodedTransaction.Output output, ProtocolParams pp) {
        var cclParams = new com.bloxbean.cardano.client.api.model.ProtocolParams();
        cclParams.setCoinsPerUtxoSize(pp.coinsPerUtxoByte().toString());
        return new MinAdaCalculator(cclParams).calculateMinAda(output.raw());
    }

    /**
     * Compatibility entry point; replay repeats every payment-binding check and may consult
     * the chain. Callers must establish submission provenance before invoking it.
     */
    public VerifyResponse verifyReplayProfile(PaymentPayload payload, PaymentRequirements requirements,
                                              String journaledNonce, String journaledPayer) {
        String nonce = str(payload.payload(), "nonce");
        try {
            if (nonce == null || !normalizeNonce(nonce).equals(journaledNonce))
                return VerifyResponse.invalid(ErrorCodes.NONCE_NOT_IN_INPUTS, null, "");
        } catch (RuntimeException e) { return VerifyResponse.invalid(ErrorCodes.NONCE_INVALID, null, ""); }
        return verifyBroadcast(payload, requirements, journaledPayer);
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
            return (address.getBytes()[0] & 0x0f) == expectedNetworkId;
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
