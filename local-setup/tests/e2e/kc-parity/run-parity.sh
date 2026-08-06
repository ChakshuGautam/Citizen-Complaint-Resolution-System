#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# run-parity.sh — capture the same journey on both deployments and diff them.
#
#   BASELINE = the default citizen/employee UI (authProvider=digit)
#   KC       = the parallel instance (authProvider=keycloak)
#
# Both serve the IDENTICAL digit-ui build; only globalConfigs differs. Any
# divergence is therefore a token-exchange-svc / Keycloak realm problem.
#
#   ./run-parity.sh boot
#   ./run-parity.sh employee --user=bometadmin --pass=eGov@123
#   ./run-parity.sh citizen  --mobile=712345678 --otp=123456
# ---------------------------------------------------------------------------
set -uo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JOURNEY="${1:-boot}"; shift || true

BASELINE="${BASELINE_URL:-https://bometfeedbackhub.digit.org}"
KC="${KC_URL:-https://kc.egov-digit.theflywheel.in}"
OUTDIR="${OUTDIR:-$DIR/out}"
mkdir -p "$OUTDIR"

echo "▶ journey=$JOURNEY"
echo "  baseline: $BASELINE"
echo "  keycloak: $KC"
echo

node "$DIR/capture.js" --base="$BASELINE" --journey="$JOURNEY" --out="$OUTDIR/$JOURNEY-baseline.json" "$@"
node "$DIR/capture.js" --base="$KC"       --journey="$JOURNEY" --out="$OUTDIR/$JOURNEY-kc.json"       "$@"
echo
node "$DIR/diff.js" "$OUTDIR/$JOURNEY-baseline.json" "$OUTDIR/$JOURNEY-kc.json" --json="$OUTDIR/$JOURNEY-report.json"
