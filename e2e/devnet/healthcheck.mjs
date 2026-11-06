// Healthy = Yano serves genesis with the expected magic AND blocks are being
// produced right now. A merely-up API is not enough: the facilitator's readiness
// rejects a tip older than its max tip age.
const base = 'http://127.0.0.1:6060/api/v1';

async function json(path) {
  const res = await fetch(base + path, { signal: AbortSignal.timeout(4000) });
  if (!res.ok) throw new Error(`${path}: HTTP ${res.status}`);
  return res.json();
}

try {
  const genesis = await json('/genesis');
  if (genesis.network_magic !== 2) throw new Error(`network_magic ${genesis.network_magic}`);
  const tip = await json('/blocks/latest');
  const age = Date.now() / 1000 - tip.time;
  if (!(age >= -30 && age <= 60)) throw new Error(`tip age ${age}s`);
  process.exit(0);
} catch (e) {
  console.error(String(e.message ?? e));
  process.exit(1);
}
