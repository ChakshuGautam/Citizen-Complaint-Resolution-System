#!/usr/bin/env bash
# Employee-inbox multi-complaint check.
#
# WHY THIS EXISTS (Task 5 finding, concern #1):
#   PGR's WorkflowService.enrichWorkflow() joins EVERY complaint in a search
#   result to its workflow in ONE call:
#       POST {wf}/egov-workflow-v2/egov-wf/process/_search?businessIds=<csv>
#   and then throws
#       CustomException("WORKFLOW_NOT_FOUND", "The workflow object is not found")
#   unless processInstances.size() == serviceRequestIds.size()
#   (service/WorkflowService.java:145).
#   DIGIT 3.0's GET /workflow/v3/transition?entityId= serves exactly ONE
#   entityId, so any employee inbox holding >= 2 complaints cannot be enriched.
#
#   lifecycle.sh never catches this: BOTH of its searches are scoped to a
#   single serviceRequestId, so the csv is always one element long. This script
#   is the missing instrument. lifecycle.sh is deliberately NOT modified - it
#   must stay byte-comparable against baseline-20260920-0238.json.
#
# WHAT IT DOES
#   1. citizen auth
#   2. create complaint A
#   3. create complaint B          <- two, so the csv has two elements
#   4. employee auth
#   5. employee complaint search on (tenantId, ids=<A>,<B>) - an inbox-shaped
#      query returning TWO complaints, NOT scoped to one serviceRequestId
#   6. assert BOTH A and B come back in ServiceWrappers
#
# Exit 0 only when both complaints are returned. As with lifecycle.sh, `set -e`
# is deliberately NOT used: a failing step is the data we want recorded.
#
# Usage:
#   bash /opt/hybrid-shim/baseline/inbox-check.sh
#   OUT=/opt/hybrid-shim/runs/inbox-2x.json bash inbox-check.sh
set -uo pipefail

: "${OUT:=/opt/hybrid-shim/runs/inbox-$(date +%Y%m%d-%H%M%S).json}"

# --- 2.x endpoints -----------------------------------------------------------
: "${KONG:=http://localhost:18000}"
: "${BASIC_AUTH:=Basic ZWdvdi11c2VyLWNsaWVudDo=}"

# --- tenancy / master data ---------------------------------------------------
: "${TENANT:=pg}"
: "${SERVICE_CODE:=BurningOfGarbage}"
: "${LOCALITY:=SUN01_LOCALITY}"
: "${SOURCE:=web}"

# --- credentials -------------------------------------------------------------
: "${CITIZEN_USER:=9999009999}"
: "${CITIZEN_PASS:=123456}"
: "${EMP_USER:=PGGRO1}"
: "${EMP_PASS:=eGov@123}"

# The inbox query is scoped with `ids` (the complaints' uuids), which is in
# employee.allowed.search.params. A tenant-wide query such as
# ?applicationStatus=PENDINGFORASSIGNMENT was tried first and is NOT a valid
# instrument on this box: the tenant already holds complaints whose workflow
# lives in the OTHER engine's store (every complaint created while
# EGOV_WORKFLOW_HOST pointed at the shim has its process instance in DIGIT 3.0,
# and every older one has it in egov-workflow-v2). Such a query therefore
# returns WORKFLOW_NOT_FOUND on BOTH backends, for a reason that has nothing to
# do with the multi-id gap. Scoping to the two complaints this script just
# created keeps the csv at exactly two ids, both of them written to whichever
# engine is currently wired in - so the ONLY variable left is whether the
# workflow search can answer for two ids at once.

: "${SEARCH_RETRIES:=12}"
: "${SEARCH_SLEEP:=2}"

mkdir -p "$(dirname "$OUT")"
: > "$OUT"

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
FAILED=0

record() {  # record <step> <http_code> <body_file>
  python3 - "$1" "$2" "$3" "$OUT" <<'PY'
import json,sys
step,code,body_file,out=sys.argv[1:5]
try: body=json.load(open(body_file))
except Exception: body=open(body_file).read()[:4000]
rec={"step":step,"http_code":int(code),"body":body}
with open(out,"a") as f: f.write(json.dumps(rec,sort_keys=True)+"\n")
PY
}

redact_token_copy() {  # redact_token_copy <src> <dst>
  python3 - "$1" "$2" <<'PY'
import json,sys
src,dst=sys.argv[1:3]
try: d=json.load(open(src))
except Exception:
    open(dst,"w").write(open(src).read()); sys.exit(0)
if isinstance(d,dict):
    for k in ("access_token","refresh_token"):
        if k in d: d[k]="<redacted>"
json.dump(d,open(dst,"w"))
PY
}

pass() { echo "PASS $1"; }
fail() { echo "FAIL $1 $2"; FAILED=1; }
die()  { echo "ABORT: $1" >&2; echo; echo "records: $OUT"; exit 1; }
jqp()  { python3 -c "import sys,json;d=json.load(sys.stdin);$1" 2>/dev/null; }

# ---------------------------------------------------------------- 1. citizen auth
CODE=$(curl -s -o "$TMP/citizen_token.json" -w '%{http_code}' \
  -H "Authorization: $BASIC_AUTH" \
  -d "grant_type=password&username=${CITIZEN_USER}&password=${CITIZEN_PASS}&tenantId=${TENANT}&userType=CITIZEN&scope=read" \
  "$KONG/user/oauth/token")
redact_token_copy "$TMP/citizen_token.json" "$TMP/citizen_token.rec.json"
record citizen_auth "$CODE" "$TMP/citizen_token.rec.json"
CITIZEN_TOKEN=$(jqp 'print(d["access_token"])' < "$TMP/citizen_token.json")
CITIZEN_INFO=$(jqp 'print(json.dumps(d["UserRequest"]))' < "$TMP/citizen_token.json")
if [ "$CODE" = 200 ] && [ -n "$CITIZEN_TOKEN" ]; then pass citizen_auth; else fail citizen_auth "$CODE"; die "no citizen token"; fi

# ---------------------------------------------------------------- 2/3. create x2
# Sets the globals CREATED_SRID / CREATED_ID rather than echoing them:
# pass()/fail() write to stdout, so a $(...) capture here would swallow the
# progress lines into the id.
CREATED_SRID=
CREATED_ID=
create_complaint() {  # create_complaint <step>; sets CREATED_SRID and CREATED_ID
  local step=$1
  CREATED_SRID=
  CREATED_ID=
  cat > "$TMP/$step.json" <<JSON
{
  "service": {
    "tenantId": "${TENANT}",
    "serviceCode": "${SERVICE_CODE}",
    "description": "hybrid-shim inbox-check ${step}",
    "additionalDetail": {},
    "source": "${SOURCE}",
    "address": {
      "landmark": "Inbox Check Landmark",
      "city": "${TENANT}",
      "district": "${TENANT}",
      "region": "${TENANT}",
      "pincode": "",
      "locality": { "code": "${LOCALITY}", "name": "${LOCALITY}" },
      "geoLocation": {}
    }
  },
  "workflow": { "action": "APPLY" },
  "RequestInfo": {
    "apiId": "Rainmaker",
    "authToken": "${CITIZEN_TOKEN}",
    "userInfo": ${CITIZEN_INFO},
    "msgId": "inbox-check|en_IN",
    "plainAccessRequest": {}
  }
}
JSON
  local code
  code=$(curl -s -o "$TMP/${step}_res.json" -w '%{http_code}' -X POST \
    "$KONG/pgr-services/v2/request/_create?tenantId=${TENANT}" \
    -H 'Content-Type: application/json' --data-binary @"$TMP/$step.json")
  record "$step" "$code" "$TMP/${step}_res.json"
  local srid uuid
  srid=$(jqp 'print(d["ServiceWrappers"][0]["service"]["serviceRequestId"])' < "$TMP/${step}_res.json")
  uuid=$(jqp 'print(d["ServiceWrappers"][0]["service"]["id"])' < "$TMP/${step}_res.json")
  if [ "$code" = 200 ] && [ -n "$srid" ] && [ -n "$uuid" ]; then
    pass "$step ($srid)"
    CREATED_SRID="$srid"
    CREATED_ID="$uuid"
    return 0
  fi
  fail "$step" "$code"
  echo "  body=$(head -c 400 "$TMP/${step}_res.json")" >&2
  return 1
}

create_complaint create_a || die "first create failed"
SRID_A="$CREATED_SRID"; ID_A="$CREATED_ID"
create_complaint create_b || die "second create failed"
SRID_B="$CREATED_SRID"; ID_B="$CREATED_ID"
if [ "$SRID_A" = "$SRID_B" ]; then die "both creates returned the same id ($SRID_A) - not a multi-id test"; fi

# ---------------------------------------------------------------- 4. employee auth
CODE=$(curl -s -o "$TMP/emp_token.json" -w '%{http_code}' \
  -H "Authorization: $BASIC_AUTH" \
  -d "grant_type=password&username=${EMP_USER}&password=${EMP_PASS}&tenantId=${TENANT}&userType=EMPLOYEE&scope=read" \
  "$KONG/user/oauth/token")
redact_token_copy "$TMP/emp_token.json" "$TMP/emp_token.rec.json"
record employee_auth "$CODE" "$TMP/emp_token.rec.json"
EMP_TOKEN=$(jqp 'print(d["access_token"])' < "$TMP/emp_token.json")
EMP_INFO=$(jqp 'print(json.dumps(d["UserRequest"]))' < "$TMP/emp_token.json")
if [ "$CODE" = 200 ] && [ -n "$EMP_TOKEN" ]; then pass employee_auth; else fail employee_auth "$CODE"; die "no employee token"; fi

# ---------------------------------------------------------------- 5. inbox search
# NOT scoped to a serviceRequestId: this is the query an employee inbox really
# issues, and it is the only way enrichWorkflow() ever builds a csv of >1 id.
cat > "$TMP/search_body.json" <<JSON
{"RequestInfo":{"apiId":"Rainmaker","authToken":"${EMP_TOKEN}","userInfo":${EMP_INFO},"msgId":"inbox-check|en_IN","plainAccessRequest":{}}}
JSON

SEARCH_URL="$KONG/pgr-services/v2/request/_search?tenantId=${TENANT}&ids=${ID_A},${ID_B}"

FOUND=0
CODE=000
for i in $(seq 1 "$SEARCH_RETRIES"); do
  CODE=$(curl -s -o "$TMP/inbox.json" -w '%{http_code}' -X POST "$SEARCH_URL" \
    -H 'Content-Type: application/json' --data-binary @"$TMP/search_body.json")
  if [ "$CODE" != 200 ]; then
    # A non-200 is the answer, not a transient: stop and record it verbatim.
    echo "  inbox search returned HTTP $CODE on attempt $i" >&2
    break
  fi
  BOTH=$(python3 - "$TMP/inbox.json" "$SRID_A" "$SRID_B" <<'PY'
import json,sys
d=json.load(open(sys.argv[1])); a,b=sys.argv[2],sys.argv[3]
ids={w.get("service",{}).get("serviceRequestId") for w in d.get("ServiceWrappers") or []}
print("yes" if a in ids and b in ids else "no")
PY
)
  [ "$BOTH" = yes ] && { FOUND=1; break; }
  sleep "$SEARCH_SLEEP"
done
record inbox_search "$CODE" "$TMP/inbox.json"

# ---------------------------------------------------------------- 6. assertion
python3 - "$TMP/inbox.json" "$SRID_A" "$SRID_B" "$CODE" <<'PY'
import json,sys
f,a,b,code=sys.argv[1:5]
try: d=json.load(open(f))
except Exception: d={}
ws=d.get("ServiceWrappers") or []
ids=[w.get("service",{}).get("serviceRequestId") for w in ws]
print("  http_code           : %s" % code)
print("  wrappers returned   : %d" % len(ws))
print("  complaint A (%s): %s" % (a, "present" if a in ids else "MISSING"))
print("  complaint B (%s): %s" % (b, "present" if b in ids else "MISSING"))
errs=d.get("Errors") or []
for e in errs:
    print("  error               : %s / %s" % (e.get("code"), e.get("message")))
PY

if [ "$FOUND" = 1 ]; then
  pass "inbox_search (both complaints enriched, multi-id workflow join OK)"
else
  fail inbox_search "$CODE"
  echo "  the employee inbox could not return both complaints." >&2
  echo "  raw body: $(head -c 600 "$TMP/inbox.json")" >&2
fi

echo
echo "complaint A:  $SRID_A"
echo "complaint B:  $SRID_B"
echo "search:       $SEARCH_URL"
echo "records:      $OUT"
exit $FAILED
