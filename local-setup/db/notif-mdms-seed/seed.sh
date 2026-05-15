#!/usr/bin/env bash
# Seeds notification MDMS schemas + per-tenant data into a running DIGIT.
#
# Required env:
#   DIGIT_URL          — Kong gateway (default: http://localhost:18000)
#   DIGIT_USERNAME     — admin user (default: ADMIN)
#   DIGIT_PASSWORD     — admin password (default: eGov@123)
#   TENANT             — target tenant (e.g. subhashini.kericho)
#   TWILIO_ACCOUNT_SID
#   TWILIO_AUTH_TOKEN
#   TWILIO_FROM        — e.g. +19789991227 for SMS, whatsapp:+14155238886 for WA sandbox
#
# Idempotent: re-runs no-op on existing records (MDMS v2 dedupes by
# (tenantId, schemaCode, uniqueIdentifier)).

set -euo pipefail

DIGIT_URL="${DIGIT_URL:-http://localhost:18000}"
DIGIT_USERNAME="${DIGIT_USERNAME:-ADMIN}"
DIGIT_PASSWORD="${DIGIT_PASSWORD:-eGov@123}"
: "${TENANT:?must set TENANT (e.g. subhashini.kericho)}"
: "${TWILIO_ACCOUNT_SID:?must set TWILIO_ACCOUNT_SID}"
: "${TWILIO_AUTH_TOKEN:?must set TWILIO_AUTH_TOKEN}"
: "${TWILIO_FROM:?must set TWILIO_FROM (e.g. +19789991227)}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${TENANT%%.*}"

echo "==> Seeding notif MDMS for tenant=$TENANT (root=$ROOT)"

# 1. Auth
TOKEN="$(curl -fsS -X POST "$DIGIT_URL/user/oauth/token" \
  -u 'egov-user-client:' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode "username=$DIGIT_USERNAME" \
  --data-urlencode "password=$DIGIT_PASSWORD" \
  --data-urlencode "grant_type=password" \
  --data-urlencode "scope=read" \
  --data-urlencode "tenantId=$ROOT" \
  --data-urlencode "userType=EMPLOYEE" \
  | jq -r '.access_token')"

[[ -z "$TOKEN" ]] && { echo "auth failed"; exit 1; }
echo "    got token: ${TOKEN:0:8}..."

# 2. Register schemas (idempotent — duplicate returns 400 + we swallow it)
for schema in TemplateBinding ProviderDetail; do
  echo "==> Registering schema: $schema"
  body="$(jq -cn \
    --arg code "$schema" \
    --arg tenant "$ROOT" \
    --argjson definition "$(cat "$SCRIPT_DIR/schemas/$schema.json")" \
    '{
      RequestInfo: { authToken: env.TOKEN, apiId: "Rainmaker" },
      SchemaDefinition: {
        tenantId: $tenant,
        code: $code,
        description: $code,
        definition: $definition,
        isActive: true
      }
    }')"
  TOKEN="$TOKEN" curl -sS -X POST \
    "$DIGIT_URL/mdms-v2/schema/v1/_create" \
    -H "Content-Type: application/json" \
    -d "$body" \
    -o /tmp/seed-resp.json -w '    %{http_code}\n' || true
  if grep -qE 'DUPLICATE|already exist' /tmp/seed-resp.json 2>/dev/null; then
    echo "    (already exists — fine)"
  fi
done

# 3. Seed data records (per-tenant substitution)
TWILIO_FROM_JSON="$TWILIO_FROM"
for f in "$SCRIPT_DIR/data/template-bindings.json" "$SCRIPT_DIR/data/provider-details.json"; do
  echo "==> Seeding $(basename "$f")"
  rendered="$(sed \
    -e "s|{{TENANT}}|$TENANT|g" \
    -e "s|{{TWILIO_ACCOUNT_SID}}|$TWILIO_ACCOUNT_SID|g" \
    -e "s|{{TWILIO_AUTH_TOKEN}}|$TWILIO_AUTH_TOKEN|g" \
    -e "s|{{TWILIO_FROM}}|$TWILIO_FROM_JSON|g" \
    "$f")"

  echo "$rendered" | jq -c '.[]' | while read -r record; do
    schema="$(echo "$record" | jq -r '.schemaCode')"
    data="$(echo "$record" | jq -c '.data')"
    body="$(jq -cn \
      --arg tenant "$TENANT" \
      --arg schema "$schema" \
      --argjson data "$data" \
      '{
        RequestInfo: { authToken: env.TOKEN, apiId: "Rainmaker" },
        Mdms: {
          tenantId: $tenant,
          schemaCode: $schema,
          data: $data,
          isActive: true
        }
      }')"
    TOKEN="$TOKEN" curl -sS -X POST \
      "$DIGIT_URL/mdms-v2/v2/_create/$schema" \
      -H "Content-Type: application/json" \
      -d "$body" \
      -o /tmp/seed-resp.json -w '    create %{http_code}\n' || true
    if grep -qE 'DUPLICATE|already exist|unique' /tmp/seed-resp.json 2>/dev/null; then
      echo "      (already exists — fine)"
    fi
  done
done

echo
echo "Seed complete. Verify with:"
echo "  curl -X POST '$DIGIT_URL/mdms-v2/v2/_search' \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"RequestInfo\":{\"apiId\":\"Rainmaker\"},\"MdmsCriteria\":{\"tenantId\":\"$TENANT\",\"schemaCode\":\"TemplateBinding\",\"limit\":10}}'"
