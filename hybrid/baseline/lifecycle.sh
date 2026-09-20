#!/usr/bin/env bash
# 2.x (DIGIT 2.12 / CCRS) baseline PGR lifecycle: create -> assign -> resolve.
#
# Drives one complaint through the live PGR workflow over the public 2.x API
# surface only (Kong gateway). No DB writes, no container changes.
#
# Exits 0 only when every step passes. Writes one JSON record per HTTP call to
# $OUT so later runs (with dependencies re-pointed at the hybrid shim) can be
# diffed against this baseline.
#
# Usage:
#   OUT=/opt/hybrid-shim/baseline/baseline-$(date +%Y%m%d-%H%M).json bash lifecycle.sh
#
# Every input is overridable by env so the same script can be re-run unchanged
# against a shimmed stack.

set -uo pipefail

: "${OUT:=/opt/hybrid-shim/baseline/baseline-$(date +%Y%m%d-%H%M).json}"

# --- 2.x endpoints -----------------------------------------------------------
: "${KONG:=http://localhost:18000}"           # CCRS 2.x Kong gateway
: "${BASIC_AUTH:=Basic ZWdvdi11c2VyLWNsaWVudDo=}"   # egov-user-client:

# --- tenancy / master data ---------------------------------------------------
: "${TENANT:=pg}"                              # complaint tenant
: "${SERVICE_CODE:=BurningOfGarbage}"          # RAINMAKER-PGR.ComplaintHierarchy leaf
: "${LOCALITY:=SUN01_LOCALITY}"                # boundary code valid for $TENANT
: "${SOURCE:=web}"                             # must be in pgr allowed.source

# --- credentials -------------------------------------------------------------
: "${CITIZEN_USER:=9999009999}"
: "${CITIZEN_PASS:=123456}"
: "${EMP_USER:=PGGRO1}"                        # holds GRO + PGR_LME on $TENANT
: "${EMP_PASS:=eGov@123}"
: "${ASSIGNEE_UUID:=}"                         # defaults to the employee's own uuid

: "${SEARCH_RETRIES:=10}"
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
except Exception: body=open(body_file).read()[:2000]
rec={"step":step,"http_code":int(code),"body":body}
with open(out,"a") as f: f.write(json.dumps(rec,sort_keys=True)+"\n")
PY
}

# An auth response carries a live bearer token (valid ~3.5 days here). Copy the
# body and strip the token before it is recorded: the baseline file is a
# long-lived artifact that later tasks diff, and a token is both a secret and
# pure diff noise.
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

die() { echo "ABORT: $1" >&2; exit 1; }

jqp() { python3 -c "import sys,json;d=json.load(sys.stdin);$1" 2>/dev/null; }

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

# ---------------------------------------------------------------- 2. create
cat > "$TMP/create.json" <<JSON
{
  "service": {
    "tenantId": "${TENANT}",
    "serviceCode": "${SERVICE_CODE}",
    "description": "hybrid-shim baseline lifecycle",
    "additionalDetail": {},
    "source": "${SOURCE}",
    "address": {
      "landmark": "Baseline Landmark",
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
    "msgId": "baseline|en_IN",
    "plainAccessRequest": {}
  }
}
JSON
CODE=$(curl -s -o "$TMP/create_res.json" -w '%{http_code}' -X POST \
  "$KONG/pgr-services/v2/request/_create?tenantId=${TENANT}" \
  -H 'Content-Type: application/json' --data-binary @"$TMP/create.json")
record create "$CODE" "$TMP/create_res.json"
SRID=$(jqp 'print(d["ServiceWrappers"][0]["service"]["serviceRequestId"])' < "$TMP/create_res.json")
if [ "$CODE" = 200 ] && [ -n "$SRID" ]; then pass "create ($SRID)"; else fail create "$CODE"; die "create failed"; fi

# ---------------------------------------------------------------- 3. citizen search (poll)
FOUND=0
for i in $(seq 1 "$SEARCH_RETRIES"); do
  cat > "$TMP/search.json" <<JSON
{"RequestInfo":{"apiId":"Rainmaker","authToken":"${CITIZEN_TOKEN}","userInfo":${CITIZEN_INFO},"msgId":"baseline|en_IN","plainAccessRequest":{}}}
JSON
  CODE=$(curl -s -o "$TMP/search_res.json" -w '%{http_code}' -X POST \
    "$KONG/pgr-services/v2/request/_search?tenantId=${TENANT}&serviceRequestId=${SRID}" \
    -H 'Content-Type: application/json' --data-binary @"$TMP/search.json")
  STATUS=$(jqp 'print(d["ServiceWrappers"][0]["service"]["applicationStatus"])' < "$TMP/search_res.json")
  [ "$CODE" = 200 ] && [ "$STATUS" = PENDINGFORASSIGNMENT ] && { FOUND=1; break; }
  sleep "$SEARCH_SLEEP"
done
record search_after_create "$CODE" "$TMP/search_res.json"
if [ "$FOUND" = 1 ]; then pass "search_after_create (PENDINGFORASSIGNMENT)"; else fail search_after_create "$CODE"; die "complaint not visible / wrong status: $STATUS"; fi

# ---------------------------------------------------------------- 4. employee auth
CODE=$(curl -s -o "$TMP/emp_token.json" -w '%{http_code}' \
  -H "Authorization: $BASIC_AUTH" \
  -d "grant_type=password&username=${EMP_USER}&password=${EMP_PASS}&tenantId=${TENANT}&userType=EMPLOYEE&scope=read" \
  "$KONG/user/oauth/token")
redact_token_copy "$TMP/emp_token.json" "$TMP/emp_token.rec.json"
record employee_auth "$CODE" "$TMP/emp_token.rec.json"
EMP_TOKEN=$(jqp 'print(d["access_token"])' < "$TMP/emp_token.json")
EMP_INFO=$(jqp 'print(json.dumps(d["UserRequest"]))' < "$TMP/emp_token.json")
EMP_UUID=$(jqp 'print(d["UserRequest"]["uuid"])' < "$TMP/emp_token.json")
if [ "$CODE" = 200 ] && [ -n "$EMP_TOKEN" ]; then pass employee_auth; else fail employee_auth "$CODE"; die "no employee token"; fi
[ -n "$ASSIGNEE_UUID" ] || ASSIGNEE_UUID="$EMP_UUID"

# The workflow _update call must echo back the whole service object it just read.
wf_update() {  # wf_update <step> <action> <expected_status>
  local step=$1 action=$2 expect=$3
  python3 - "$TMP/search_res.json" "$TMP/wf_body.json" "$action" "$ASSIGNEE_UUID" "$EMP_TOKEN" "$EMP_INFO" <<'PY'
import json,sys
src,dst,action,assignee,token,empinfo=sys.argv[1:7]
svc=json.load(open(src))["ServiceWrappers"][0]["service"]
body={"workflow":{"action":action,"assignes":[assignee],"comments":"baseline "+action,
                  "verificationDocuments":[]},
      "service":svc,
      "RequestInfo":{"apiId":"Rainmaker","authToken":token,
                     "userInfo":json.loads(empinfo),
                     "msgId":"baseline|en_IN","plainAccessRequest":{}}}
json.dump(body,open(dst,"w"))
PY
  local code
  code=$(curl -s -o "$TMP/wf_res.json" -w '%{http_code}' -X POST \
    "$KONG/pgr-services/v2/request/_update" \
    -H 'Content-Type: application/json' --data-binary @"$TMP/wf_body.json")
  record "$step" "$code" "$TMP/wf_res.json"
  local got
  got=$(jqp 'print(d["ServiceWrappers"][0]["service"]["applicationStatus"])' < "$TMP/wf_res.json")
  if [ "$code" = 200 ] && [ "$got" = "$expect" ]; then
    pass "$step ($got)"
    # feed the updated service into the next action
    cp "$TMP/wf_res.json" "$TMP/search_res.json"
    return 0
  fi
  fail "$step" "$code"
  echo "  expected applicationStatus=$expect got=$got body=$(head -c 400 "$TMP/wf_res.json")" >&2
  return 1
}

# ---------------------------------------------------------------- 5. assign
wf_update assign ASSIGN PENDINGATLME || die "ASSIGN failed"

# ---------------------------------------------------------------- 6. resolve
wf_update resolve RESOLVE RESOLVED || die "RESOLVE failed"

# ---------------------------------------------------------------- 7. final search
cat > "$TMP/search.json" <<JSON
{"RequestInfo":{"apiId":"Rainmaker","authToken":"${EMP_TOKEN}","userInfo":${EMP_INFO},"msgId":"baseline|en_IN","plainAccessRequest":{}}}
JSON
CODE=$(curl -s -o "$TMP/final.json" -w '%{http_code}' -X POST \
  "$KONG/pgr-services/v2/request/_search?tenantId=${TENANT}&serviceRequestId=${SRID}" \
  -H 'Content-Type: application/json' --data-binary @"$TMP/search.json")
record search_final "$CODE" "$TMP/final.json"
FINAL=$(jqp 'print(d["ServiceWrappers"][0]["service"]["applicationStatus"])' < "$TMP/final.json")
if [ "$CODE" = 200 ] && [ "$FINAL" = RESOLVED ]; then pass "search_final (RESOLVED)"; else fail search_final "$CODE"; echo "  final applicationStatus=$FINAL" >&2; fi

echo
echo "serviceRequestId: $SRID"
echo "records:          $OUT"
exit $FAILED
