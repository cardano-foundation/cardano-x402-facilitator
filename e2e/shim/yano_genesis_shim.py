"""Test-only Blockfrost compatibility shim between the facilitator and Yano.

Why it exists: Yano's Blockfrost-compatible ``GET /genesis`` returns
``system_start`` as an ISO-8601 string; Blockfrost (and cardano-client-lib's
``Genesis`` model) define it as integer UNIX seconds. Jackson rejects the string,
the facilitator's network-identity check fails, and readiness stays DOWN.
Upstream issue: TODO(link once filed).

What it changes: ONLY the ``system_start`` field of a successful ``GET */genesis``.
The facilitator reads nothing from genesis but ``network_magic``; its slot clock
comes from the rendered ``slot-config``. Every other request and response passes
through unchanged (status, body, content type).

What it adds (never forwarded upstream): ``GET /__shim/submits?sha256=<hex>`` returns
how many ``POST */tx/submit`` requests carried a body with that SHA-256, so the
suite can prove a payment was broadcast exactly once.

The ``/sdk`` prefix (``/sdk/api/v1/...``) is a second view, for the TypeScript
SDK suite only. The Evolution SDK's Blockfrost provider validates responses
strictly, and Yano differs from Blockfrost in two more places:
``GET */addresses/{addr}/utxos`` omits ``tx_index`` (added, = ``output_index``),
and ``GET */epochs/latest/parameters`` returns ``drep_deposit`` and
``gov_action_deposit`` as numbers (stringified). The facilitator never uses
``/sdk``, so its view stays exactly the plain pass-through above.
Upstream issue: TODO(link once filed).

Opt-in faults and latency (facilitator view only; ``/sdk/*`` is never touched). With nothing
registered every request is a byte-for-byte pass-through. The control API is handled here and
never forwarded:

  POST   /__shim/faults      {id, match: {txHash|sha256|pathRegex|outRef}, action: {type, ...}, times?}
  DELETE /__shim/faults      remove all;   DELETE /__shim/faults/<id>   remove one
  POST   /__shim/profile     {latencyMs?, ratePerSec?, burst?}      DELETE /__shim/profile

``match`` keys are ANDed. ``sha256`` (of the submit body) matches only ``POST */tx/submit``;
``txHash`` matches ``*/txs/<hash>[/...]`` and ``*/mempool/<hash>``; ``outRef`` ("<hash>#<index>")
matches ``*/txs/<hash>/utxos`` (the URL carries no index, so a status fault there hits every
output of that tx; ``drop-lovelace`` alone is index-precise); ``pathRegex`` is searched in the path.
``times`` limits how often a fault fires (it is removed after the last firing). Actions:
``drop-response`` (forward a submit, answer 502), ``swallow`` (no forward, 200 with
``action.txHash``), ``ledger-reject`` (no forward, 400 with ``action.body``), ``status``
(``action.status`` and optional ``action.body``, no forward), ``mempool`` (``*/mempool/<hash>``
answers 200), ``hide-tx`` (``*/txs/<hash>`` and sub-paths answer 404; ``*/mempool/<hash>`` is NOT
touched), ``drop-lovelace`` (the ``lovelace`` entry of output ``outRef`` is removed from the
``*/txs/<hash>/utxos`` response). The submit counter above keeps counting EVERY
``POST */tx/submit`` body received, faulted or not.

Profile: every field is optional. ``latencyMs`` delays every facilitator-view request. Only when
``ratePerSec`` is given, requests over a token bucket (``burst``, default ``ratePerSec``, at
least 1) answer 429 with a Blockfrost-shaped body; ``POST */tx/submit`` is always exempt from the
rate limit (a 429 there would be an Unknown submit outcome).

Usage: python -I yano_genesis_shim.py <upstream-base-url> [port]
"""

import datetime
import gzip
import hashlib
import http.server
import json
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

UPSTREAM = sys.argv[1].rstrip("/")
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 16061
FORWARDED_HEADERS = ("content-type", "project_id", "accept")

_submits = {}
_submits_lock = threading.Lock()


_faults = []  # dicts: id, match, action, times (None = unlimited)
_profile = None
_bucket = {"tokens": 0.0, "at": 0.0}
_state_lock = threading.Lock()

READ_ACTIONS = ("status", "mempool", "hide-tx", "drop-lovelace")
SUBMIT_ACTIONS = ("drop-response", "swallow", "ledger-reject")
HASH_RE = r"([0-9a-fA-F]{64})"
REASONS = {400: "Bad Request", 404: "Not Found", 418: "IP has been auto-banned", 425: "Mempool Full",
           429: "Too Many Requests", 500: "Internal Server Error", 502: "Bad Gateway", 503: "Service Unavailable"}


def _blockfrost_error(code, message=None):
    reason = REASONS.get(code, "Error")
    return json.dumps({"status_code": code, "error": reason, "message": message or reason}).encode()


def _is_submit(command, path):
    return command == "POST" and path.rstrip("/").endswith("/tx/submit")


def _tx_hash_of(path):
    m = re.search(r"/(?:txs|mempool)/" + HASH_RE, path)
    return m.group(1).lower() if m else None


def _matches(fault, command, path, digest):
    match = fault["match"]
    submit = _is_submit(command, path)
    if "sha256" in match and not (submit and digest == match["sha256"].lower()):
        return False
    if "pathRegex" in match and not re.search(match["pathRegex"], path):
        return False
    if "txHash" in match and _tx_hash_of(path) != match["txHash"].lower():
        return False
    if "outRef" in match:
        m = re.search(r"/txs/" + HASH_RE + r"/utxos/?$", path)
        if command != "GET" or not m or m.group(1).lower() != match["outRef"].split("#")[0].lower():
            return False
    return True


def _applicable(fault, command, path):
    kind = fault["action"]["type"]
    if kind in SUBMIT_ACTIONS:
        return _is_submit(command, path)
    if command != "GET":
        return False
    if kind == "mempool":
        return re.search(r"/mempool/" + HASH_RE + r"/?$", path) is not None
    if kind == "hide-tx":
        return re.search(r"/txs/" + HASH_RE + r"(/|$)", path) is not None
    if kind == "drop-lovelace":
        return re.search(r"/txs/" + HASH_RE + r"/utxos/?$", path) is not None
    return True  # status: any read path its match selects


def _fire(command, path, digest):
    """The first registered fault that applies to this request, or None; consumes one use."""
    with _state_lock:
        for fault in list(_faults):
            if _applicable(fault, command, path) and _matches(fault, command, path, digest):
                if fault["times"] is not None:
                    fault["times"] -= 1
                    if fault["times"] <= 0:
                        _faults.remove(fault)
                return fault
    return None


def _rate_limited():
    with _state_lock:
        profile = _profile
        if not profile or profile.get("ratePerSec") is None:
            return False
        rate = float(profile["ratePerSec"])
        burst = max(1.0, float(profile.get("burst") or rate))
        now = time.monotonic()
        _bucket["tokens"] = min(burst, _bucket["tokens"] + (now - _bucket["at"]) * rate)
        _bucket["at"] = now
        if _bucket["tokens"] >= 1.0:
            _bucket["tokens"] -= 1.0
            return False
        return True


def _drop_lovelace(data, out_ref):
    index = int(out_ref.split("#")[1])
    doc = json.loads(data)
    for output in doc.get("outputs", []):
        if output.get("output_index") == index:
            output["amount"] = [a for a in output.get("amount", []) if a.get("unit") != "lovelace"]
    return json.dumps(doc).encode()


SDK_PREFIX = "/sdk"
STRING_PARAMS = ("drep_deposit", "gov_action_deposit")


def _rewrite_utxos(body):
    utxos = json.loads(body)
    if isinstance(utxos, list):
        for utxo in utxos:
            if isinstance(utxo, dict) and "tx_index" not in utxo and "output_index" in utxo:
                utxo["tx_index"] = utxo["output_index"]
    return json.dumps(utxos).encode()


def _rewrite_parameters(body):
    params = json.loads(body)
    for key in STRING_PARAMS:
        if isinstance(params.get(key), (int, float)):
            params[key] = str(params[key])
    return json.dumps(params).encode()


def _rewrite_genesis(body):
    genesis = json.loads(body)
    start = genesis.get("system_start")
    if isinstance(start, str):
        parsed = datetime.datetime.fromisoformat(start.replace("Z", "+00:00"))
        genesis["system_start"] = int(parsed.timestamp())
    return json.dumps(genesis).encode()


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send(self, code, body, content_type):
        self.send_response(code)
        self.send_header("Content-Type", content_type or "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _shim_endpoint(self, body):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path.rstrip("/")
        if path == "/__shim/faults":
            return self._faults_endpoint(body)
        if path.startswith("/__shim/faults/") and self.command == "DELETE":
            fault_id = urllib.parse.unquote(path[len("/__shim/faults/"):])
            with _state_lock:
                _faults[:] = [f for f in _faults if f["id"] != fault_id]
            return self._send(200, b'{"deleted":true}', "application/json")
        if path == "/__shim/profile":
            return self._profile_endpoint(body)
        query = urllib.parse.parse_qs(parsed.query)
        digest = (query.get("sha256") or [""])[0].lower()
        with _submits_lock:
            count = _submits.get(digest, 0)
        self._send(200, json.dumps({"sha256": digest, "count": count}).encode(), "application/json")

    def _bad(self, message):
        self._send(400, json.dumps({"error": message}).encode(), "application/json")

    def _faults_endpoint(self, body):
        if self.command == "DELETE":
            with _state_lock:
                _faults[:] = []
            return self._send(200, b'{"deleted":true}', "application/json")
        if self.command != "POST":
            return self._bad("unsupported method")
        try:
            spec = json.loads(body or b"")
            fault_id, match, action = spec["id"], spec["match"], spec["action"]
            times = spec.get("times")
            assert isinstance(fault_id, str) and isinstance(match, dict) and isinstance(action, dict)
            assert match and set(match) <= {"txHash", "sha256", "pathRegex", "outRef"}
            assert all(isinstance(v, str) for v in match.values())
            assert action.get("type") in READ_ACTIONS + SUBMIT_ACTIONS
            if action["type"] == "swallow":
                assert isinstance(action.get("txHash"), str)
            if action["type"] == "ledger-reject":
                assert isinstance(action.get("body"), (str, dict, list))
            assert times is None or (isinstance(times, int) and times > 0)
            if "pathRegex" in match:
                re.compile(match["pathRegex"])
            if action["type"] == "drop-lovelace":
                assert "#" in str(match.get("outRef", ""))
                int(match["outRef"].split("#")[1])
            if action["type"] == "status":
                assert isinstance(action.get("status"), int) and not isinstance(action["status"], bool)
        except (ValueError, KeyError, TypeError, AssertionError, re.error) as e:
            return self._bad(f"invalid fault: {e!r}")
        with _state_lock:
            _faults[:] = [f for f in _faults if f["id"] != fault_id]
            _faults.append({"id": fault_id, "match": match, "action": action, "times": times})
        self._send(200, json.dumps({"id": fault_id}).encode(), "application/json")

    def _profile_endpoint(self, body):
        global _profile
        if self.command == "DELETE":
            with _state_lock:
                _profile = None
            return self._send(200, b'{"deleted":true}', "application/json")
        if self.command != "POST":
            return self._bad("unsupported method")
        try:
            spec = json.loads(body or b"")
            assert isinstance(spec, dict) and set(spec) <= {"latencyMs", "ratePerSec", "burst"}
            for value in spec.values():
                assert isinstance(value, (int, float)) and value >= 0
        except (ValueError, TypeError, AssertionError) as e:
            return self._bad(f"invalid profile: {e!r}")
        with _state_lock:
            _profile = spec
            burst = max(1.0, float(spec.get("burst") or spec.get("ratePerSec") or 1))
            _bucket["tokens"], _bucket["at"] = burst, time.monotonic()
        self._send(200, b'{"ok":true}', "application/json")

    def _apply_fault(self, action, body, upstream_path, headers):
        """Answers for a fired fault; returns False when the request should proceed (drop-lovelace)."""
        kind = action["type"]
        if kind == "swallow":
            self._send(200, json.dumps(action["txHash"]).encode(), "application/json")
        elif kind == "ledger-reject":
            payload = action["body"]
            self._send(400, (payload if isinstance(payload, str) else json.dumps(payload)).encode(), "application/json")
        elif kind == "status":
            payload = action.get("body")
            data = _blockfrost_error(int(action["status"])) if payload is None else (
                payload if isinstance(payload, str) else json.dumps(payload)).encode()
            self._send(int(action["status"]), data, "application/json")
        elif kind == "mempool":
            h = _tx_hash_of(upstream_path)
            self._send(200, json.dumps({"tx": {"hash": h}, "inputs": [], "outputs": [], "redeemers": []}).encode(),
                       "application/json")
        elif kind == "hide-tx":
            self._send(404, _blockfrost_error(404, "The requested component has not been found."), "application/json")
        elif kind == "drop-response":
            request = urllib.request.Request(UPSTREAM + upstream_path, data=body, method=self.command, headers=headers)
            try:
                urllib.request.urlopen(request, timeout=30).read()
            except (urllib.error.URLError, TimeoutError):
                pass  # the caller still gets the 502 below
            self._send(502, _blockfrost_error(502), "application/json")
        else:
            return False
        return True

    def _proxy(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        if self.path.startswith("/__shim/"):
            return self._shim_endpoint(body)
        if self.command == "DELETE":
            return self.send_error(501, "Unsupported method ('DELETE')")
        sdk = self.path.startswith(SDK_PREFIX + "/")
        upstream_path = self.path[len(SDK_PREFIX):] if sdk else self.path
        path = urllib.parse.urlparse(upstream_path).path
        if self.command == "POST" and path.rstrip("/").endswith("/tx/submit") and body is not None:
            digest = hashlib.sha256(body).hexdigest()
            with _submits_lock:
                _submits[digest] = _submits.get(digest, 0) + 1
        headers = {k: v for k, v in self.headers.items() if k.lower() in FORWARDED_HEADERS}
        drop_lovelace = None
        if not sdk:
            with _state_lock:
                profile = _profile
            if profile and profile.get("latencyMs"):
                time.sleep(profile["latencyMs"] / 1000.0)
            digest = hashlib.sha256(body).hexdigest() if body is not None else ""
            # _fire uses up one of the fault's `times` here, before the rate-limit check below and
            # before upstream answers: a request then answered 429, or a drop-lovelace whose upstream
            # is not 200, still spends that firing. No test combines a profile with a counted fault.
            fault = _fire(self.command, path, digest)
            if fault is not None:
                if fault["action"]["type"] == "drop-lovelace":
                    drop_lovelace = fault["match"]["outRef"]
                elif self._apply_fault(fault["action"], body, upstream_path, headers):
                    return
            if (profile and profile.get("ratePerSec") is not None and not _is_submit(self.command, path)
                    and _rate_limited()):
                return self._send(429, _blockfrost_error(429, "Usage is over limit."), "application/json")
        request = urllib.request.Request(UPSTREAM + upstream_path, data=body, method=self.command, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                code, data, content_type = response.status, response.read(), response.headers.get("Content-Type")
                encoding = response.headers.get("Content-Encoding")
        except urllib.error.HTTPError as e:
            code, data, content_type = e.code, e.read(), e.headers.get("Content-Type")
            encoding = e.headers.get("Content-Encoding")
        except (urllib.error.URLError, TimeoutError) as e:
            return self._send(502, json.dumps({"error": f"shim upstream: {e}"}).encode(), "application/json")
        # Content-Encoding is not forwarded, so always hand the client a plain body.
        if encoding == "gzip":
            data = gzip.decompress(data)
        if self.command == "GET" and code == 200 and path.rstrip("/").endswith("/genesis"):
            data = _rewrite_genesis(data)
        if drop_lovelace is not None and code == 200:
            data = _drop_lovelace(data, drop_lovelace)
        if sdk and self.command == "GET" and code == 200 and path.rstrip("/").endswith("/utxos"):
            data = _rewrite_utxos(data)
        if sdk and self.command == "GET" and code == 200 and path.rstrip("/").endswith("/parameters"):
            data = _rewrite_parameters(data)
        self._send(code, data, content_type)

    do_GET = do_POST = do_DELETE = _proxy

    def log_message(self, fmt, *args):
        sys.stderr.write("shim %s - %s\n" % (self.command, fmt % args))


if __name__ == "__main__":
    server = http.server.ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    server.daemon_threads = True
    print(f"shim listening on :{PORT} -> {UPSTREAM}", flush=True)
    server.serve_forever()
