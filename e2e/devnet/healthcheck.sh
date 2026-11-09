#!/usr/bin/env bash
# Healthy = Yano serves genesis with the expected magic AND blocks are being
# produced right now. A merely-up API is not enough: the facilitator's readiness
# rejects a tip older than its max tip age.
#
# Bash and curl only (the image has no Node, Python or jq), so the JSON is matched
# with anchored patterns: a value must be followed by a non-digit, and anything
# that does not match fails the check.
set -euo pipefail
base=http://127.0.0.1:6060/api/v1

unhealthy() { echo "$*" >&2; exit 1; }

genesis=$(curl -fsS -m 4 "$base/genesis") || unhealthy "/genesis unavailable"
[[ $genesis =~ \"network_magic\"[[:space:]]*:[[:space:]]*2[^0-9] ]] || unhealthy "genesis network_magic is not 2"

tip=$(curl -fsS -m 4 "$base/blocks/latest") || unhealthy "/blocks/latest unavailable"
[[ $tip =~ \"time\"[[:space:]]*:[[:space:]]*([0-9]+)[^0-9] ]] || unhealthy "latest block has no numeric time"
age=$(($(date +%s) - BASH_REMATCH[1]))
((age >= -30 && age <= 60)) || unhealthy "tip age ${age}s"
