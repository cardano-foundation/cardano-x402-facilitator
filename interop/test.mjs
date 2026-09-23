import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { HTTPFacilitatorClient } from "@x402/core/http";
import { x402ResourceServer } from "@x402/core/server";
import { ERR_SETTLEMENT_DEFINITIVELY_REJECTED } from "@x402/cardano";
import { ExactCardanoScheme } from "@x402/cardano/exact/server";

const url = process.argv[2];
assert.ok(url, "Run with ./gradlew interop (starts the Java test server)");
const vectors = JSON.parse(await readFile(new URL("../src/test/resources/upstream/payments.json", import.meta.url), "utf8"));
const client = new HTTPFacilitatorClient({ url });
let checks = 0;
async function check(name, run) {
  await run();
  checks++;
  console.log(`PASS ${name}`);
}
async function control(name, changes = {}) {
  const response = await fetch(`${url}/__interop/control`, { method: "POST",
    headers: { "Content-Type": "application/json" }, body: JSON.stringify({ name, ...changes }) });
  assert.equal(response.status, 200);
  return response.json();
}
const payment = name => vectors.payments.find(p => p.name === name);
const settle = name => { const p = payment(name); return client.settle(p.payload, p.requirements); };
function pending(response, p) {
  assert.equal(response.success, false, JSON.stringify(response));
  assert.equal(response.errorReason, "settlement_pending");
  assert.equal(response.transaction, p.txHash);
  assert.equal(response.extra.transactionId, p.txHash);
  assert.equal(response.extra.status, "pending");
}
function confirmed(response, p, confirmations = 3) {
  assert.equal(response.success, true, JSON.stringify(response));
  assert.equal(response.transaction, p.txHash);
  assert.equal(response.payer, p.payer);
  assert.equal(response.extra.transactionId, p.txHash);
  assert.equal(response.extra.status, "confirmed");
  assert.equal(response.extra.confirmations, confirmations);
}

const server = new x402ResourceServer(client);
for (const network of ["cardano:preprod", "cardano:preview", "cardano:mainnet"]) {
  server.register(network, new ExactCardanoScheme());
}
await check("TypeScript resource server accepts Java capabilities", async () => {
  await server.initialize();
  const supported = await client.getSupported();
  assert.equal(supported.kinds.length, 3);
  for (const kind of supported.kinds) {
    assert.deepEqual(kind.extra.l1Confirmations, { minimum: 0, maximum: 20 });
    assert.equal(kind.extra.submissionModes, undefined);
    assert.equal(kind.extra.settlementLayers, undefined);
  }
});
for (const p of vectors.payments) {
  await check(`Java verifies upstream signed ${p.name}`, async () => {
    const response = await client.verify(p.payload, p.requirements);
    assert.equal(response.isValid, true, JSON.stringify(response));
    assert.equal(response.payer, p.payer);
  });
}
for (const p of vectors.invalidPayments) {
  await check(`Java matches upstream rejection: ${p.name}`, async () => {
    const response = await client.verify(p.payload, p.requirements);
    assert.equal(response.isValid, false, JSON.stringify(response));
    assert.equal(response.invalidReason, p.expected.invalidReason);
  });
}
await check("Removed client submission metadata cannot select a broadcast bypass", async () => {
  const p = structuredClone(payment("ada"));
  p.requirements.extra.submissionPolicy = "client";
  p.requirements.extra.settlementLayer = "l1";
  p.payload.accepted = p.requirements;
  p.payload.payload.submissionMode = "client";
  assert.equal((await client.verify(p.payload, p.requirements)).isValid, true);
});
await check("Explicit null confirmation policy is rejected", async () => {
  const p = structuredClone(payment("ada"));
  p.requirements.extra.confirmationPolicy = null;
  p.payload.accepted = p.requirements;
  assert.equal((await client.verify(p.payload, p.requirements)).isValid, false);
});
await check("TypeScript resource server retries pending once; Java broadcasts once", async () => {
  const p = payment("ada");
  const before = await control("ada", { includeAfterWait: true });
  confirmed(await server.settlePayment(p.payload, p.requirements), p);
  const after = await control("ada");
  assert.equal(after.settleCalls - before.settleCalls, 2);
  assert.equal(after.submissions, 1);
});
await check("Pending token payment survives spent inputs and a provider outage", async () => {
  const p = payment("token");
  pending(await settle("token"), p);
  await control("token", { evidence: "outage" });
  pending(await settle("token"), p);
  await control("token", { evidence: "included" });
  confirmed(await settle("token"), p);
  confirmed(await settle("token"), p);
  assert.equal((await control("token")).submissions, 1);
});
await check("Confirmed retry checks current chain evidence after rollback", async () => {
  const p = payment("token");
  await control("token", { evidence: "unknown" });
  pending(await settle("token"), p);
  await control("token", { evidence: "included", confirmations: 5 });
  confirmed(await settle("token"), p, 5);
  assert.equal((await control("token")).submissions, 1);
});
for (const name of ["script", "masumi-ada", "masumi-token", "preprod-alias"]) {
  await check(`Pending ${name} reconciles without rebroadcast`, async () => {
    const p = payment(name);
    pending(await settle(name), p);
    await control(name, { evidence: "included" });
    confirmed(await settle(name), p);
    assert.equal((await control(name)).submissions, 1);
  });
}
await check("Uncertain submission retains its claim until ledger evidence arrives", async () => {
  const p = payment("mainnet-offline");
  await control(p.name, { submit: "unknown" });
  pending(await settle(p.name), p);
  pending(await settle(p.name), p);
  await control(p.name, { evidence: "included" });
  confirmed(await settle(p.name), p);
  assert.equal((await control(p.name)).submissions, 1);
});
await check("Definitively rejected submission stays terminal", async () => {
  const p = payment("preview-offline");
  await control(p.name, { submit: "rejected" });
  assert.equal((await settle(p.name)).success, false);
  const retry = await settle(p.name);
  assert.equal(retry.success, false);
  assert.equal(retry.errorReason, ERR_SETTLEMENT_DEFINITIVELY_REJECTED);
  assert.equal((await control(p.name)).submissions, 1);
});
console.log(`TypeScript/Java interoperability: ${checks} checks passed (offline chain, real HTTP and SDKs)`);
