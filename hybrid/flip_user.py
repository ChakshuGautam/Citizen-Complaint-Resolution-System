# Flip (or revert) EGOV_USER_HOST in the pgr-services block ONLY.
#
# Original value: "http://egov-user-proxy:8107/" (line 1812). Unlike
# EGOV_WORKFLOW_HOST, which occurs exactly once in the whole file, this
# variable appears SIX times - egov-workflow-v2, boundary-service, egov-hrms,
# the user-bootstrap job and others all carry their own copy. Only
# pgr-services' copy may move: every other service must keep talking to the
# real 2.x user service, or the switchover stops being a single-dependency
# experiment. The block scan below is what enforces that, and it asserts there
# is exactly ONE match inside the block rather than editing the first hit.
#
# egov-user-proxy is a pure nginx pass-through to egov-user:8107
# (/opt/digit/nginx/user-proxy.conf: `location / { proxy_pass
# http://$user_upstream:8107; }`) - it adds no behaviour.
#
# WHAT THIS DOES NOT MOVE: the gateway's session check. kong-gateway issues
# `POST /user/_details?access_token=...` against egov-user through its OWN
# upstream configuration, not through this variable (proved by grouping the
# egov-user-proxy access log by source container IP: the _details calls come
# from 172.19.0.35 = kong-gateway, never from 172.19.0.38 = pgr-services). So
# CCRS logins keep working after this flip, and keep minting 2.x user uuids
# that the v3 individual registry has never heard of. That is the whole finding
# of Task 6.
#
# The trailing slash matters: pgr-services concatenates
#   egov.user.host + egov.user.search.path            -> /user/_search
#   egov.user.host + egov.user.context.path + create  -> /user/users/_createnovalidate
#   egov.user.host + egov.user.context.path + update  -> /user/users/_updatenovalidate
import io, sys
P = "/opt/digit/docker-compose.egov-digit.yaml"
ORIG = "      EGOV_USER_HOST: http://egov-user-proxy:8107/\n"
SHIM = "      EGOV_USER_HOST: http://hybrid-shim:9100/\n"
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
assert len(hits) == 1, "expected exactly one EGOV_USER_HOST in pgr-services, got %s" % hits
lines[hits[0]] = new
io.open(P, "w", encoding="utf-8").writelines(lines)
print("set line %d -> %s" % (hits[0] + 1, new.strip()))
