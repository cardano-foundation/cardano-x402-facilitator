package org.cardanofoundation.x402.facilitator.e2e;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.backend.model.TransactionContent;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The full x402 flow, end to end, against a REAL network (preprod by default):
 *
 *   1. build + sign a payment with cardano-client-lib (the facilitator never signs),
 *   2. POST /verify — the facilitator must accept the payment,
 *   3. POST /settle — the facilitator submits and confirms it,
 *   4. independently prove the tx is ON-CHAIN via a direct provider lookup of the
 *      LOCALLY computed tx hash (never the facilitator's word for it).
 *
 * Owner-directed preprod test credentials are coded as env-overridable defaults
 * (testnet-only; rotate after the exercise). The harness is network-configurable
 * so the same class runs the yaci-devkit devnet E2E (E2E_NETWORK, E2E_BACKEND_URL).
 *
 * Run: BLOCKFROST_PROJECT_ID=... ./gradlew e2e   (facilitator must be running)
 */
public class X402PreprodE2E {

    static final String BF_PROJECT = env("BLOCKFROST_PROJECT_ID", "preprodwv4rjfmnCJsuYNpZWGb9zBAfvoRH7T22");
    static final String BACKEND_URL = env("E2E_BACKEND_URL", "https://cardano-preprod.blockfrost.io/api/v0/");
    static final String MNEMONIC = env("E2E_MNEMONIC",
            "base sun bonus asset priority twenty puppy rural animal public rural symbol tilt crowd "
                    + "grape claim fury satisfy wing churn ginger essence cigar nasty");
    static final String FACILITATOR = env("FACILITATOR_URL", "http://localhost:4022");
    static final String NETWORK = env("E2E_NETWORK", "cardano:preprod");
    static final BigInteger AMOUNT = new BigInteger(env("E2E_AMOUNT_LOVELACE", "1500000"));
    static final String NOT_CONFIRMED = "exact_cardano_settlement_not_confirmed";

    public static void main(String[] args) throws Exception {
        BackendService backend = new BFBackendService(BACKEND_URL, BF_PROJECT);
        Network network = "cardano:mainnet".equals(NETWORK) ? Networks.mainnet() : Networks.testnet();
        Account account = new Account(network, MNEMONIC);
        String from = account.baseAddress();
        // receiver = same wallet, address index 1 (indexed factory; getBaseAddress(int) does not exist)
        String payTo = Account.createFromMnemonic(network, MNEMONIC, 0, 1).baseAddress();
        System.out.println("payer:    " + from);
        System.out.println("receiver: " + payTo);

        long tip = backend.getBlockService().getLatestBlock().getValue().getSlot();
        long ttl = tip + 1800;

        // 1. Build + sign the payment (client pays the fee; facilitator never signs)
        QuickTxBuilder quickTx = new QuickTxBuilder(backend);
        Tx tx = new Tx().payToAddress(payTo, Amount.lovelace(AMOUNT)).from(from);
        Transaction signed = quickTx.compose(tx)
                .validTo(ttl)
                .withSigner(SignerProviders.signerFrom(account))
                .buildAndSign();
        byte[] txBytes = signed.serialize();
        String txB64 = Base64.getEncoder().encodeToString(txBytes);
        TransactionInput in0 = signed.getBody().getInputs().get(0);
        String nonce = in0.getTransactionId().toLowerCase() + "#" + in0.getIndex();
        String expectedHash = TransactionUtil.getTxHash(txBytes).toLowerCase();
        System.out.println("built tx " + expectedHash + " (nonce " + nonce + ", ttl slot " + ttl + ")");

        // 2. x402 verify + settle against the running facilitator
        Map<String, Object> requirements = new LinkedHashMap<>();
        requirements.put("scheme", "exact");
        requirements.put("network", NETWORK);
        requirements.put("asset", "lovelace");
        requirements.put("amount", AMOUNT.toString());
        requirements.put("payTo", payTo);
        requirements.put("maxTimeoutSeconds", 600);
        requirements.put("extra", Map.of("assetTransferMethod", "default"));
        Map<String, Object> body = Map.of(
                "x402Version", 2,
                "paymentPayload", Map.of(
                        "x402Version", 2,
                        "resource", Map.of("url", "https://example.test/report", "description", "e2e"),
                        "accepted", requirements,
                        "payload", Map.of("transaction", txB64, "nonce", nonce)),
                "paymentRequirements", requirements);

        ObjectMapper om = new ObjectMapper();
        HttpClient http = HttpClient.newHttpClient();

        JsonNode verify = post(http, om, FACILITATOR + "/verify", body);
        System.out.println("verify:   " + verify);
        require(verify.path("isValid").asBoolean(), "facilitator rejected the payment: " + verify);
        require(from.equals(verify.path("payer").asText()), "payer mismatch: " + verify.path("payer"));

        JsonNode settle = post(http, om, FACILITATOR + "/settle", body);
        System.out.println("settle:   " + settle);
        boolean settled = settle.path("success").asBoolean();
        if (!settled && !NOT_CONFIRMED.equals(settle.path("errorReason").asText())) {
            throw new IllegalStateException("settle failed: " + settle); // hard failure, not a timeout
        }
        if (settled) {
            require(expectedHash.equalsIgnoreCase(settle.path("transaction").asText()),
                    "facilitator returned a DIFFERENT tx hash: " + settle.path("transaction").asText()
                            + " (expected " + expectedHash + ")");
        } else {
            System.out.println("facilitator timed out awaiting confirmation; tx was submitted — polling chain");
        }

        // 3. Independent on-chain proof: direct provider lookup of the LOCAL hash
        long deadline = System.currentTimeMillis() + 300_000;
        String block = null;
        while (System.currentTimeMillis() < deadline) {
            Result<TransactionContent> onChain = backend.getTransactionService().getTransaction(expectedHash);
            if (onChain.isSuccessful() && onChain.getValue().getBlock() != null) {
                block = onChain.getValue().getBlock();
                break;
            }
            Thread.sleep(5000);
        }
        require(block != null, "tx not on-chain within 5 minutes: " + expectedHash);
        System.out.println();
        System.out.println("ON-CHAIN CONFIRMED: tx " + expectedHash + " in block " + block);
        System.out.println("  https://preprod.cardanoscan.io/transaction/" + expectedHash);
    }

    private static JsonNode post(HttpClient http, ObjectMapper om, String url, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        require(response.statusCode() == 200, url + " -> HTTP " + response.statusCode() + ": " + response.body());
        return om.readTree(response.body());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }
}
