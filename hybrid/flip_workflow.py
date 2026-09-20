# Flip (or revert) EGOV_WORKFLOW_HOST in the pgr-services block ONLY.
#
# Original value: "http://egov-workflow-proxy:8109/" (line 1809) - and note
# that this is the ONLY occurrence of EGOV_WORKFLOW_HOST in the whole file, so
# unlike idgen (4 copies) and boundary (3), there is no neighbour to protect.
# The block scan is kept anyway so the refusal-on-ambiguity behaviour matches
# the other two flips.
#
# egov-workflow-proxy is a pure nginx pass-through to egov-workflow-v2:8109
# (/opt/digit/nginx/workflow-proxy.conf: `location / { proxy_pass
# http://$workflow_upstream:8109; }`) - it adds no behaviour, so pointing the
# variable at the shim replaces the workflow service and nothing else.
#
# The trailing slash matters: pgr-services concatenates
#   egov.workflow.host + egov.workflow.{transition,businessservice.search,
#                                      processinstance.search}.path
# and each of those paths starts with "/egov-workflow-v2/...".
import io, sys
P = "/opt/digit/docker-compose.egov-digit.yaml"
ORIG = "      EGOV_WORKFLOW_HOST: http://egov-workflow-proxy:8109/\n"
SHIM = "      EGOV_WORKFLOW_HOST: http://hybrid-shim:9100/\n"
want = sys.argv[1]  # 'shim' or 'orig'
new, old = (SHIM, ORIG) if want == "shim" else (ORIG, SHIM)
lines = io.open(P, encoding="utf-8").readlines()
start = next(i for i, l in enumerate(lines) if l.rstrip() == "  pgr-services:")
end = next((i for i in range(start + 1, len(lines))
            if lines[i].startswith("  ") and not lines[i].startswith("   ") and lines[i].strip().endswith(":")),
           len(lines))
hits = [i for i in range(start, end) if lines[i] == old]
if not hits:
    already = [i for i in range(start, end) if lines[i] == new]
    print("already %s (line %s)" % (want, [i + 1 for i in already]))
    sys.exit(0)
assert len(hits) == 1, "expected exactly one EGOV_WORKFLOW_HOST in pgr-services, got %s" % hits
lines[hits[0]] = new
io.open(P, "w", encoding="utf-8").writelines(lines)
print("set line %d -> %s" % (hits[0] + 1, new.strip()))
