// The published @x402 TypeScript SDK pays through the facilitator CONTAINER on the
// devnet, for every transfer method. Nothing here is hand-built: the resource
// server issues the 402, the reference client signer builds and signs against
// Yano, HTTPFacilitatorClient verifies and settles, and Yano — not the
// facilitator — confirms the transaction. Run via e2e/run.sh --ts.
import { before, describe, test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { Assets, Client, NativeScripts, PrivateKey, ScriptHash, preview } from "@evolution-sdk/evolution";
import { HTTPFacilitatorClient } from "@x402/core/http";
import { x402ResourceServer } from "@x402/core/server";
import { ExactCardanoScheme as ServerScheme } from "@x402/cardano/exact/server";
import { ExactCardanoScheme as ClientScheme } from "@x402/cardano/exact/client";
import { decodeCardanoTransaction, toClientCardanoSigner, toMasumiSellerSigner } from "@x402/cardano";

const NETWORK = "cardano:preview";
const env = (name) => {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set — run through e2e/run.sh --ts`);
  return value.replace(/\/$/, "");
};
const FACILITATOR_URL = env("FACILITATOR_URL");
const FACILITATOR_MEMPOOL_URL = env("FACILITATOR_MEMPOOL_URL");
// Independent on-chain checks read Yano directly. The SDK's own provider reads the
// shim's /sdk view, which adds the two fields Yano's Blockfrost-compatible API
// lacks and Evolution's provider requires (see e2e/shim/yano_genesis_shim.py).
const CHAIN_URL = env("E2E_CHAIN_URL");
const SDK_CHAIN_URL = env("E2E_SDK_CHAIN_URL");
const ADMIN_URL = env("E2E_ADMIN_URL");
const MASUMI = JSON.parse(await readFile(env("E2E_MASUMI_DEPLOYMENT"), "utf8")).allowlisted;
const PROVIDER = { blockfrost: { baseUrl: SDK_CHAIN_URL, projectId: "devnet" }, requestTimeoutMs: 30_000 };
const RESOURCE = { url: "https://e2e.test/resource", description: "devnet e2e", mimeType: "application/json" };

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Polls `probe` until it returns a non-undefined value, or fails after `ms`. */
async function until(what, ms, probe) {
  const end = Date.now() + ms;
  for (;;) {
    const value = await probe().catch(() => undefined);
    if (value !== undefined) return value;
    if (Date.now() > end) throw new Error(`timed out after ${ms} ms waiting for ${what}`);
    await sleep(500);
  }
}

async function chain(path) {
  const response = await fetch(`${CHAIN_URL}${path}`, { headers: { project_id: "devnet" } });
  return response.status === 404 ? undefined : response.json();
}

/** Funds a fresh wallet from the DevKit faucet and waits until the UTxO is visible. */
async function fundedSigner(ada, extraConfig = {}) {
  const mnemonic = PrivateKey.generateMnemonic();
  const signer = toClientCardanoSigner({ mnemonic, network: NETWORK, provider: PROVIDER, ...extraConfig });
  const response = await fetch(`${ADMIN_URL}/local-cluster/api/addresses/topup`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ address: signer.getAddress(), adaAmount: ada }),
  });
  assert.equal(response.status, 200, `topup failed: ${await response.text()}`);
  await until(`topup of ${signer.getAddress()}`, 120_000, async () => {
    const utxos = await chain(`/addresses/${signer.getAddress()}/utxos`);
    return utxos?.length ? true : undefined;
  });
  return { mnemonic, signer };
}

/**
 * Mints 1000 of a fresh token to the wallet, under a native-script policy of the
 * wallet's own payment key, with Evolution. Waits until Yano shows the mint.
 *
 * @returns the canonical x402 asset id `<policyHex>.<assetNameHex>`.
 */
async function mintToken(mnemonic, name) {
  const client = Client.make(preview).withBlockfrost(PROVIDER.blockfrost).withSeed({ mnemonic });
  const address = await client.address();
  const script = NativeScripts.makeScriptPubKey(address.paymentCredential.hash);
  const policyId = ScriptHash.toHex(ScriptHash.fromScript(script)).toLowerCase();
  const nameHex = Buffer.from(name).toString("hex");
  const built = await client.newTx().attachScript({ script })
    .mintAssets({ assets: Assets.addByHex(Assets.fromLovelace(0n), policyId, nameHex, 1000n) }).build();
  const submitted = await (await built.sign()).submit();
  await assertOnChain(Buffer.from(submitted.hash).toString("hex"));
  return `${policyId}.${nameHex}`;
}

/** Waits until Yano itself shows the tx in a block, by the facilitator-reported hash. */
async function assertOnChain(txHash) {
  const tx = await until(`tx ${txHash} on-chain`, 120_000, async () => {
    const content = await chain(`/txs/${txHash}`);
    return content?.block ? content : undefined;
  });
  assert.equal(tx.hash, txHash);
  return tx;
}

let facilitator;
let receiverAddress;

before(async () => {
  // The devnet poses as preview but has its own genesis start: point the shared
  // preview chain object (used by the SDK for slot <-> time) at the devnet, the
  // same override the facilitator gets through its NETWORKS_FILE slot-config.
  const devnet = await (await fetch(`${ADMIN_URL}/local-cluster/api/admin/devnet`)).json();
  assert.equal(devnet.protocolMagic, 2, "devnet must pose as preview");
  preview.slotConfig.zeroTime = BigInt(devnet.startTime) * 1000n;
  preview.slotConfig.zeroSlot = 0n;
  preview.slotConfig.slotLength = Math.round(devnet.slotLength * 1000);

  facilitator = new HTTPFacilitatorClient({ url: FACILITATOR_URL });
  receiverAddress = toClientCardanoSigner({
    mnemonic: PrivateKey.generateMnemonic(), network: NETWORK, provider: PROVIDER,
  }).getAddress();
});

/** Issues a 402 for `resourceConfig` and returns its first requirements. */
async function issue(resourceServer, resourceConfig) {
  const built = await resourceServer.buildPaymentRequirements(resourceConfig);
  const required = await resourceServer.createPaymentRequiredResponse(built, RESOURCE);
  assert.ok(required.accepts?.length, "402 carries requirements");
  return required.accepts[0];
}

/** Client builds + signs, the facilitator verifies + settles, Yano confirms. */
async function payAndSettle(signer, requirements) {
  const client = new ClientScheme(signer);
  const result = await client.createPaymentPayload(2, requirements, { resource: RESOURCE });
  const payload = { x402Version: 2, resource: RESOURCE, accepted: requirements, ...result };

  const verified = await facilitator.verify(payload, requirements);
  assert.equal(verified.isValid, true, `verify: ${JSON.stringify(verified)}`);
  assert.equal(verified.payer, signer.getAddress());

  // The hash is computed here, from the bytes the client signed — never taken from the facilitator.
  const localHash = decodeCardanoTransaction(result.payload.transaction).txHash.toLowerCase();

  const settled = await facilitator.settle(payload, requirements);
  assert.equal(settled.success, true, `settle: ${JSON.stringify(settled)}`);
  assert.equal(settled.network, NETWORK);
  assert.equal(settled.payer, signer.getAddress());
  assert.equal(settled.transaction, localHash, "the facilitator settled exactly the signed transaction");
  await assertOnChain(localHash);
  return settled;
}

describe("published TS SDK through the facilitator container", () => {
  test("SDK parses /supported from both instances", async () => {
    for (const [url, minimum] of [[FACILITATOR_URL, 0], [FACILITATOR_MEMPOOL_URL, -1]]) {
      const supported = await new HTTPFacilitatorClient({ url }).getSupported();
      const kind = supported.kinds.find((k) => k.network === NETWORK && k.scheme === "exact");
      assert.ok(kind, `${url} advertises exact on ${NETWORK}`);
      assert.deepEqual(kind.extra.assetTransferMethods, ["default", "masumi", "script"]);
      assert.equal(kind.extra.l1Confirmations.minimum, minimum);
    }
  });

  test("default method, ADA", async () => {
    const { signer } = await fundedSigner(20);
    const requirements = await issue(await defaultServer(), {
      scheme: "exact", network: NETWORK, payTo: receiverAddress, maxTimeoutSeconds: 300,
      price: { asset: "lovelace", amount: "2000000" },
    });
    const settled = await payAndSettle(signer, requirements);
    assert.equal(settled.extra?.status, "confirmed");
  });

  test("default method, native token", async () => {
    const { mnemonic, signer } = await fundedSigner(20);
    const asset = await mintToken(mnemonic, "E2ETS");
    const requirements = await issue(await defaultServer(), {
      scheme: "exact", network: NETWORK, payTo: receiverAddress, maxTimeoutSeconds: 300,
      price: { asset, amount: "250" },
    });
    assert.equal(requirements.asset, asset);
    await payAndSettle(signer, requirements);
  });

  test("script method (always-succeeds PlutusV3, payTo its script address)", async () => {
    const { signer } = await fundedSigner(20);
    const requirements = await issue(await defaultServer(), {
      scheme: "exact", network: NETWORK, maxTimeoutSeconds: 300,
      payTo: "addr_test1wp8l7eylksmjas7ypzm0q35dwnjdxxvsfn0z0lflqzgs55stpd682",
      price: { asset: "lovelace", amount: "2000000" },
      extra: { assetTransferMethod: "script", script: { type: "plutusV3", code: "4d01000033222220051200120011" } },
    });
    await payAndSettle(signer, requirements);
  });

  test("masumi method, ADA lock into the allowlisted devnet deployment", async () => {
    const { signer } = await fundedSigner(50, masumiBuyerConfig());
    const requirements = await issue(await masumiServer(), {
      scheme: "exact", network: NETWORK, payTo: MASUMI.escrowAddress, maxTimeoutSeconds: 300,
      price: { asset: "lovelace", amount: "5000000" },
      extra: { assetTransferMethod: "masumi" },
    });
    assert.equal(requirements.payTo, MASUMI.escrowAddress, "issuer derives the allowlisted escrow");
    await payAndSettle(signer, requirements);
  });

  test("masumi method, native-token lock", async () => {
    const { mnemonic, signer } = await fundedSigner(50, masumiBuyerConfig());
    const asset = await mintToken(mnemonic, "E2EMASUMI");
    const requirements = await issue(await masumiServer(), {
      scheme: "exact", network: NETWORK, payTo: MASUMI.escrowAddress, maxTimeoutSeconds: 300,
      price: { asset, amount: "100" },
      extra: { assetTransferMethod: "masumi" },
    });
    assert.equal(requirements.asset, asset);
    await payAndSettle(signer, requirements);
  });
});

async function defaultServer() {
  const resourceServer = new x402ResourceServer(facilitator).register(NETWORK, new ServerScheme());
  await resourceServer.initialize();
  return resourceServer;
}

/** A resource server that issues seller-signed Masumi terms for the devnet deployment. */
async function masumiServer() {
  const seller = toMasumiSellerSigner({ mnemonic: PrivateKey.generateMnemonic(), network: NETWORK });
  const resourceServer = new x402ResourceServer(facilitator)
    .register(NETWORK, new ServerScheme({ masumi: { seller, deployment: MASUMI.deployment } }));
  await resourceServer.initialize();
  return resourceServer;
}

/** The devnet deployment is non-canonical: the buyer approves exactly it, nothing else. */
function masumiBuyerConfig() {
  const expected = MASUMI.deployment;
  return {
    validateCustomMasumiDeployment: ({ deployment }) =>
      deployment.requiredAdmins === expected.requiredAdmins && deployment.cooldownPeriod === expected.cooldownPeriod
      && JSON.stringify(deployment.adminVkeys) === JSON.stringify(expected.adminVkeys),
  };
}
