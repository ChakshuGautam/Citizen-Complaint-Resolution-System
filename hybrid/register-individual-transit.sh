#!/usr/bin/env bash
# Mount Vault's transit engine and create the key d3-individual encrypts PII
# with. Idempotent.
#
# WHY THIS EXISTS
#   Before this ran, EVERY create against the v3 individual registry answered
#     HTTP 500 [{"code":"ENCRYPTION_ERROR","message":"Encryption failed",
#                "description":"Failed to encrypt sensitive data",
#                "params":{"field":"mobileNumber"}}]
#   and d3-individual's own log said why:
#     vault encrypt failed: vault returned status 404:
#       {"errors":["no handler for route \"transit/encrypt/IndividualEncrypt\".
#                   route entry not found."]}
#   `vault secrets list` confirmed it: cubbyhole, identity, secret, sys - and
#   no transit engine at all. d3-individual is configured to use one
#   (VAULT_ENABLED=true, VAULT_TRANSIT_BASE_PATH=v1/transit/,
#   VAULT_ENCRYPT_KEY=IndividualEncrypt) and even knows how to create it
#   (VAULT_SYS_MOUNT_TRANSIT_PATH, VAULT_KEY_CREATE_PATH), but nothing on this
#   deployment had ever triggered that bootstrap - the registry was empty, so
#   no create had ever been attempted.
#
#   The effect is worth stating plainly, because it is a gap in its own right:
#   the v3 individual service comes up healthy, answers searches with HTTP 200
#   and an empty list, passes every readiness check - and is incapable of
#   storing a single person. Nothing in the API surface says so until you try
#   to write.
#
# This is deployment provisioning, the same kind of thing Task 5's
# register-pgr-workflow.sh does for the workflow process definition. It changes
# no CCRS state and no shim code.
set -euo pipefail

VAULT_CONTAINER="${VAULT_CONTAINER:-d3-vault}"
INDIVIDUAL_CONTAINER="${INDIVIDUAL_CONTAINER:-d3-individual}"

# Use the token the individual service itself is configured with, so the key
# lands somewhere that service can actually reach.
TOKEN=$(docker exec "$INDIVIDUAL_CONTAINER" printenv VAULT_TOKEN)
KEY=$(docker exec "$INDIVIDUAL_CONTAINER" printenv VAULT_ENCRYPT_KEY)
: "${KEY:=IndividualEncrypt}"

v() { docker exec "$VAULT_CONTAINER" sh -c "VAULT_TOKEN='$TOKEN' VAULT_ADDR=http://127.0.0.1:8200 $*"; }

if v "vault secrets list -format=json" | grep -q '"transit/"'; then
  echo "transit engine already mounted"
else
  v "vault secrets enable transit"
fi

if v "vault list transit/keys 2>/dev/null" | grep -qx "$KEY"; then
  echo "transit key $KEY already exists"
else
  v "vault write -f transit/keys/$KEY" >/dev/null
  echo "created transit key $KEY"
fi

v "vault list transit/keys"
