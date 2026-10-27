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

Usage: python -I yano_genesis_shim.py <upstream-base-url> [port]
"""

import datetime
import gzip
import hashlib
import http.server
import json
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request

UPSTREAM = sys.argv[1].rstrip("/")
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 16061
FORWARDED_HEADERS = ("content-type", "project_id", "accept")

_submits = {}
_submits_lock = threading.Lock()


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

    def _shim_endpoint(self):
        query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        digest = (query.get("sha256") or [""])[0].lower()
        with _submits_lock:
            count = _submits.get(digest, 0)
        self._send(200, json.dumps({"sha256": digest, "count": count}).encode(), "application/json")

    def _proxy(self):
        if self.path.startswith("/__shim/"):
            return self._shim_endpoint()
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        sdk = self.path.startswith(SDK_PREFIX + "/")
        upstream_path = self.path[len(SDK_PREFIX):] if sdk else self.path
        path = urllib.parse.urlparse(upstream_path).path
        if self.command == "POST" and path.rstrip("/").endswith("/tx/submit") and body is not None:
            digest = hashlib.sha256(body).hexdigest()
            with _submits_lock:
                _submits[digest] = _submits.get(digest, 0) + 1
        headers = {k: v for k, v in self.headers.items() if k.lower() in FORWARDED_HEADERS}
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
        if sdk and self.command == "GET" and code == 200 and path.rstrip("/").endswith("/utxos"):
            data = _rewrite_utxos(data)
        if sdk and self.command == "GET" and code == 200 and path.rstrip("/").endswith("/parameters"):
            data = _rewrite_parameters(data)
        self._send(code, data, content_type)

    do_GET = do_POST = _proxy

    def log_message(self, fmt, *args):
        sys.stderr.write("shim %s - %s\n" % (self.command, fmt % args))


if __name__ == "__main__":
    server = http.server.ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    server.daemon_threads = True
    print(f"shim listening on :{PORT} -> {UPSTREAM}", flush=True)
    server.serve_forever()
