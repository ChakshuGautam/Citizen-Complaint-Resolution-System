#!/usr/bin/env bash
# Compare two lifecycle.sh runs, ignoring the fields that change on every run.
#
#   compare.sh --view <run.json>              flattened, sorted comparison view
#   compare.sh <baseline.json> <run.json>     exit 0 if equivalent, 1 if not
#
# The comparison view is one fact per line ("step<TAB>path<TAB>value"), so a
# plain `diff` of two views is precise and readable. lifecycle.sh writes the
# view for its own run to "$OUT.view" automatically.
#
# Volatility is decided by PATH, not by key name, so that a stable `id`
# somewhere else (e.g. citizen_auth.UserRequest.id, which pins WHICH citizen
# authenticated) is still compared. See README.md for the rationale.

set -uo pipefail

usage() { sed -n '2,12p' "$0" >&2; exit 2; }

[ $# -ge 1 ] || usage

VIEWER=$(cat <<'PY'
import json,sys,os
from fnmatch import fnmatch

# Path suffix patterns whose values differ between runs and carry no regression
# signal. Derived empirically, not guessed: 5 real runs were diffed against each
# other and every varying leaf path is covered below (41 of 717).
IGNORE = [
    "*.expires_in",                    # token lifetime, counts down in real time
    "*.auditDetails.createdTime",      # epoch millis
    "*.auditDetails.lastModifiedTime", # epoch millis
    "*.serviceRequestId",              # PG-PGR-<date>-<counter>, new per complaint
    "*.businessId",                    # same value as serviceRequestId
    "*.service.id",                    # server-generated uuid
    "*.service.address.id",            # server-generated uuid
    "*.processInstance.id",            # server-generated uuid
    "*.businesssServiceSla",           # SLA remaining, counts down (upstream typo kept)
    # Intermittent: pgr-services usually leaves responseInfo.ts null but
    # sometimes fills it with epoch millis. Seen flipping between two otherwise
    # identical runs, so it is noise even though 5 consecutive runs agreed.
    "*.responseInfo.ts",
    "*.ResponseInfo.ts",
]

def volatile(path):
    return any(fnmatch(path, pat) for pat in IGNORE)

def leaves(o, path):
    if isinstance(o, dict):
        if not o:
            yield (path, "{}")
        for k in sorted(o):
            yield from leaves(o[k], path + "." + k)
    elif isinstance(o, list):
        if not o:
            yield (path, "[]")
        # index-insensitive: list position is not meaningful for these payloads,
        # but a changed length still shows up as added/removed lines.
        for i, v in enumerate(o):
            yield from leaves(v, path + "[%d]" % i)
    else:
        yield (path, json.dumps(o))

def view(fname):
    lines = []
    seen_steps = []
    for n, raw in enumerate(open(fname)):
        raw = raw.strip()
        if not raw:
            continue
        rec = json.loads(raw)
        step = rec["step"]
        seen_steps.append(step)
        lines.append("%s\t@order\t%d" % (step, len(seen_steps)))
        lines.append("%s\t@http_code\t%s" % (step, rec["http_code"]))
        for p, v in leaves(rec["body"], step):
            if volatile(p):
                v = '"<volatile>"'
            lines.append("%s\t%s\t%s" % (step, p, v))
    return sorted(lines)
PY
)

if [ "$1" = --view ]; then
  [ $# -eq 2 ] || usage
  python3 -c "$VIEWER
import sys
print('\n'.join(view(sys.argv[1])))" "$2"
  exit $?
fi

[ $# -eq 2 ] || usage
BASE=$1; RUN=$2
for f in "$BASE" "$RUN"; do
  [ -r "$f" ] || { echo "compare.sh: cannot read $f" >&2; exit 2; }
done

python3 -c "$VIEWER
import sys
base_f, run_f = sys.argv[1], sys.argv[2]
a, b = view(base_f), view(run_f)

def index(lines):
    d = {}
    for l in lines:
        step, path, val = l.split('\t', 2)
        d[(step, path)] = val
    return d

A, B = index(a), index(b)
only_a = sorted(set(A) - set(B))
only_b = sorted(set(B) - set(A))
changed = sorted(k for k in set(A) & set(B) if A[k] != B[k])

if not (only_a or only_b or changed):
    n = len({s for s, _ in A})
    print('EQUIVALENT: %d steps, %d compared facts, no meaningful differences.' % (n, len(A)))
    print('  baseline: %s' % base_f)
    print('  run:      %s' % run_f)
    sys.exit(0)

CAP = int(os.environ.get('COMPARE_MAX_LINES', '25'))

def steps_of(D):
    return [s for _, s in sorted((int(D[(s, p)]), s) for (s, p) in D if p == '@order')]

steps_a, steps_b = steps_of(A), steps_of(B)
missing_steps = [s for s in steps_a if s not in steps_b]
extra_steps = [s for s in steps_b if s not in steps_a]

print('DIFFERENT: %s vs %s' % (base_f, run_f))

# A step that never ran at all is one fact, not several hundred. Collapse it,
# otherwise the commonest real case -- a run that aborts partway -- buries the
# actual cause under the entire tail of the baseline.
if missing_steps:
    print()
    print('  STEPS THAT DID NOT RUN (%d):' % len(missing_steps))
    for s in missing_steps:
        n = len([1 for st, _ in only_a if st == s])
        print('    %s   (%d baseline facts unchecked)' % (s, n))
if extra_steps:
    print()
    print('  STEPS NOT IN BASELINE (%d): %s' % (len(extra_steps), ', '.join(extra_steps)))

def show(title, items, src, note=''):
    items = [k for k in items if k[0] not in missing_steps and k[0] not in extra_steps]
    if not items:
        return
    print()
    print('  %s (%d)%s:' % (title, len(items), note))
    for step, path in items[:CAP]:
        if src is None:
            print('    [%s] %s' % (step, path))
            print('        baseline: %s' % A[(step, path)])
            print('        run:      %s' % B[(step, path)])
        else:
            print('    [%s] %s = %s' % (step, path, src[(step, path)]))
    if len(items) > CAP:
        print('    ... and %d more (COMPARE_MAX_LINES=%d)' % (len(items) - CAP, CAP))

show('CHANGED VALUES', changed, None)
show('MISSING FROM RUN', only_a, A, ' - in baseline, absent now')
show('NEW IN RUN', only_b, B, ' - absent from baseline, present now')
sys.exit(1)" "$BASE" "$RUN"
