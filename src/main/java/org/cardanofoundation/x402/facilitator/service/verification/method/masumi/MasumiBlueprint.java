package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.util.HexUtil;
import org.cardanofoundation.x402.facilitator.service.registry.CardanoNetworks;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical Masumi {@code vested_pay} V2 deployment: the compiled validator, the
 * default parameters, and the escrow address they derive to.
 *
 * <p>The escrow address is <b>derived</b>, never taken on trust. A server-declared
 * {@code contractAddress} only says where the server would like the funds to go;
 * applying the deployment parameters to the canonical validator says where the
 * escrow whose rules both parties signed actually lives. Accepting the former
 * would let a resource server point a lock at any address it likes, and the
 * buyer's funds would settle outside the contract that is supposed to govern
 * their release.
 *
 * <p>Mirrors {@code blueprint.ts} in the TypeScript reference implementation:
 * the parameters are applied in the order {@code required_admins},
 * {@code admin_vkeys}, {@code cooldown_period}, and a different parameterization
 * is a different validator hash — hence a different trust domain.
 */
public final class MasumiBlueprint {

    private static final String COMPILED_CODE_RESOURCE = "/masumi/vested_pay_v2.compiled.hex";

    /** Compiled, unparameterized {@code vested_pay.vested_pay.spend} validator. */
    private static final String COMPILED_CODE = loadCompiledCode();

    /** Canonical deployment for mainnet and preprod. Preview has none. */
    public static final MasumiDeployment DEFAULT_DEPLOYMENT = new MasumiDeployment(
            BigInteger.valueOf(2L),
            List.of("fc16a1fcf309aed03ec18bb2176f5ea29acea70bb79145ebaffa8e75",
                    "7f78161369549d8e2b138fee724c9fa606d6107a66720bdb4c48ada6",
                    "89eef9ea84e0ee7fe4921fa93eb2873ff6e34473f751d5d52cb75aa6"),
            BigInteger.valueOf(420_000L));

    /** Applying parameters and hashing is pure, so memoize per parameterization. */
    private static final Map<String, String> SCRIPT_HASH_CACHE = new LinkedHashMap<>();
    private static final int MAX_CACHE_ENTRIES = 32;

    private MasumiBlueprint() {
    }

    /**
     * The three deployment parameters applied to the canonical validator. Admin
     * key order and duplicates are preserved: a repeated key carries repeated
     * voting weight and changes the resulting hash.
     *
     * @param requiredAdmins signatures needed to settle a disputed escrow.
     * @param adminVkeys ordered admin verification-key hashes (28-byte hex).
     * @param cooldownPeriod contract cooldown in milliseconds.
     */
    public record MasumiDeployment(BigInteger requiredAdmins, List<String> adminVkeys,
                                   BigInteger cooldownPeriod) {
    }

    /**
     * Derives the escrow validator hash for a deployment.
     *
     * @param deployment the deployment parameters to apply.
     * @return lowercase hex script hash.
     */
    public static String escrowScriptHash(MasumiDeployment deployment) {
        String cacheKey = deployment.requiredAdmins() + "|"
                + String.join(",", deployment.adminVkeys()) + "|"
                + deployment.cooldownPeriod();
        synchronized (SCRIPT_HASH_CACHE) {
            String cached = SCRIPT_HASH_CACHE.get(cacheKey);
            if (cached != null) return cached;
        }

        ListPlutusData adminList = ListPlutusData.builder().plutusDataList(
                deployment.adminVkeys().stream()
                        .map(vkey -> (com.bloxbean.cardano.client.plutus.spec.PlutusData)
                                BytesPlutusData.of(HexUtil.decodeHexString(vkey)))
                        .toList())
                .build();
        ListPlutusData params = ListPlutusData.builder().plutusDataList(List.of(
                BigIntPlutusData.of(deployment.requiredAdmins()),
                adminList,
                BigIntPlutusData.of(deployment.cooldownPeriod()))).build();

        String applied = AikenScriptUtil.applyParamToScript(params, COMPILED_CODE);
        String hash;
        try {
            hash = HexUtil.encodeHexString(
                    PlutusV3Script.builder().cborHex(cborWrap(applied)).build().getScriptHash())
                    .toLowerCase();
        } catch (Exception e) {
            throw new IllegalStateException("failed to hash the parameterized Masumi validator", e);
        }
        synchronized (SCRIPT_HASH_CACHE) {
            if (SCRIPT_HASH_CACHE.size() >= MAX_CACHE_ENTRIES) {
                var oldest = SCRIPT_HASH_CACHE.keySet().iterator();
                if (oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
            }
            SCRIPT_HASH_CACHE.put(cacheKey, hash);
        }
        return hash;
    }

    /**
     * Derives the bech32 enterprise escrow address for a deployment on a network.
     *
     * @param network x402 Cardano network identifier (or a CIP-34 alias).
     * @param deployment the deployment parameters to apply.
     * @return bech32 enterprise script address of the escrow.
     */
    public static String escrowAddress(String network, MasumiDeployment deployment) {
        String scriptHash = escrowScriptHash(deployment);
        var net = CardanoNetworks.networkId(network) == 1 ? Networks.mainnet() : Networks.testnet();
        return AddressProvider.getEntAddress(
                Credential.fromScript(HexUtil.decodeHexString(scriptHash)), net).toBech32();
    }

    /** Double-CBOR-wraps an applied script, matching the ledger's script envelope. */
    private static String cborWrap(String appliedHex) {
        byte[] raw = HexUtil.decodeHexString(appliedHex);
        try {
            co.nstant.in.cbor.model.ByteString bs = new co.nstant.in.cbor.model.ByteString(raw);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            new co.nstant.in.cbor.CborEncoder(out).encode(bs);
            return HexUtil.encodeHexString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("failed to CBOR-wrap the applied Masumi validator", e);
        }
    }

    private static String loadCompiledCode() {
        try (InputStream in = MasumiBlueprint.class.getResourceAsStream(COMPILED_CODE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + COMPILED_CODE_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + COMPILED_CODE_RESOURCE, e);
        }
    }
}
