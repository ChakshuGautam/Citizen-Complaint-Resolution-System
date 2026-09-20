# Flip (or revert) EGOV_BOUNDARY_HOST in the pgr-services block ONLY.
# Original value: "http://boundary-service:8081/"  (line 1814). Two other
# services carry the same key and keep theirs: egov-hrms (1514, no trailing
# slash) and egov-bndry-mgmnt (1570) - neither is pgr-services.
#
# NOTE the trailing slash matters: pgr-services concatenates
#   egov.boundary.host + egov.boundary.search.url (= /boundary-service/boundary/_search)
# and Spring's RestTemplate normalises the resulting "//" back to "/" before
# the wire, so the shim receives /boundary-service/boundary/_search.
import io, sys
P = "/opt/digit/docker-compose.egov-digit.yaml"
ORIG = "      EGOV_BOUNDARY_HOST: http://boundary-service:8081/\n"
SHIM = "      EGOV_BOUNDARY_HOST: http://hybrid-shim:9100/\n"
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
assert len(hits) == 1, "expected exactly one EGOV_BOUNDARY_HOST in pgr-services, got %s" % hits
lines[hits[0]] = new
io.open(P, "w", encoding="utf-8").writelines(lines)
print("set line %d -> %s" % (hits[0] + 1, new.strip()))
