#!/usr/bin/env bash
# register-pgr-workflow.sh
#
# Registers CCRS's PGR workflow in DIGIT 3.0 from the YAML source of truth at
# v3-config/pgr-process-definition.yaml (itself transcribed from the LIVE 2.x
# BusinessService in v3-config/pgr-businessservice-2x.json).
#
# WHY NOT `digit create-workflow --file`, which the plan names:
#   The CLI is unconfigured on this box (`digit config show` prints an empty
#   server URL and an empty JWT token) and `digit config set` authenticates
#   against Keycloak. The v3 Kong here has ZERO plugins and needs no auth, and
#   Task 3 registered the idgen template the same way — with curl — for exactly
#   this reason. The payload posted below is the CLI's payload: the YAML
#   converted to JSON, POSTed to /workflow/v3/process/definition.
#
# Idempotent: if the definition already exists, says so and exits 0.
#
#   V3=http://localhost:8000 TENANT=pg bash register-pgr-workflow.sh
set -euo pipefail

V3="${V3:-http://localhost:8000}"
TENANT="${TENANT:-pg}"
USER_ID="${USER_ID:-hybrid-shim-task5}"
HERE="$(cd "$(dirname "$0")" && pwd)"
YAML="${YAML:-$HERE/v3-config/pgr-process-definition.yaml}"
CODE="${CODE:-PGR}"

[ -f "$YAML" ] || { echo "no such file: $YAML" >&2; exit 2; }

hdr=(-H "X-Tenant-ID: $TENANT" -H "X-User-ID: $USER_ID" -H 'Content-Type: application/json')

existing=$(curl -s -m 15 "${hdr[@]}" "$V3/workflow/v3/process/definition?processCode=$CODE")
if printf '%s' "$existing" | python3 -c '
import json,sys
try: d=json.load(sys.stdin)
except Exception: sys.exit(1)
defs=d.get("definitions") or []
sys.exit(0 if any(x.get("code")=="'"$CODE"'" for x in defs) else 1)
'; then
  echo "process definition $CODE already registered on tenant $TENANT - nothing to do"
  exit 0
fi

json=$(python3 -c '
import json,sys,yaml
print(json.dumps(yaml.safe_load(open(sys.argv[1]))))
' "$YAML")

echo "registering process definition $CODE on tenant $TENANT at $V3 ..."
out=$(printf '%s' "$json" | curl -s -m 30 -w '\n%{http_code}' -X POST "${hdr[@]}" \
        --data-binary @- "$V3/workflow/v3/process/definition")
status=$(printf '%s' "$out" | tail -n1)
body=$(printf '%s' "$out" | sed '$d')

if [ "$status" != 200 ] && [ "$status" != 201 ]; then
  echo "FAILED ($status): $body" >&2
  exit 1
fi
echo "registered. now record what did not survive the crossing:"
echo "  node $HERE/record-workflow-gaps.js"
