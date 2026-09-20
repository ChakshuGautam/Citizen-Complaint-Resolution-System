# Flip (or revert) EGOV_IDGEN_HOST in the pgr-services block ONLY.
# Original value: "http://egov-idgen:8088/"  (line 1808; three other services
# on lines 972/1203/1510 keep theirs - they are NOT pgr-services).
import io, sys
P = "/opt/digit/docker-compose.egov-digit.yaml"
ORIG = "      EGOV_IDGEN_HOST: http://egov-idgen:8088/\n"
SHIM = "      EGOV_IDGEN_HOST: http://hybrid-shim:9100/\n"
want = sys.argv[1]  # 'shim' or 'orig'
new, old = (SHIM, ORIG) if want == "shim" else (ORIG, SHIM)
lines = io.open(P, encoding="utf-8").readlines()
# find the pgr-services block
start = next(i for i, l in enumerate(lines) if l.rstrip() == "  pgr-services:")
end = next((i for i in range(start + 1, len(lines))
            if lines[i].startswith("  ") and not lines[i].startswith("   ") and lines[i].strip().endswith(":")),
           len(lines))
hits = [i for i in range(start, end) if lines[i] == old]
if not hits:
    already = [i for i in range(start, end) if lines[i] == new]
    print("already %s (line %s)" % (want, [i + 1 for i in already]))
    sys.exit(0)
assert len(hits) == 1, "expected exactly one EGOV_IDGEN_HOST in pgr-services, got %s" % hits
lines[hits[0]] = new
io.open(P, "w", encoding="utf-8").writelines(lines)
print("set line %d -> %s" % (hits[0] + 1, new.strip()))
