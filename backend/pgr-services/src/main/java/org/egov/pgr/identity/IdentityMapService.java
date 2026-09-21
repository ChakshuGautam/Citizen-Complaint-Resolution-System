package org.egov.pgr.identity;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The 2.x-uuid to DIGIT 3.0 identity correspondence, and the only component allowed to state it.
 *
 * <h3>Why this exists at all</h3>
 * v3 mints its own ids and ignores a caller-supplied one (M7), and neither registry can be asked
 * "who is 2.x uuid X?" - {@code ?userId=} is accepted and answered with every record in the tenant
 * at HTTP 200 (M8). CCRS, meanwhile, holds 2.x uuids on {@code eg_pgr_service_v2.accountid}, on
 * every workflow assignee and in every audit column. Something has to remember which is which.
 *
 * <h3>The direction that matters</h3>
 * The substitution runs BOTH ways and the reverse one is the load-bearing half.
 * {@code UserService.enrichUsers} builds {@code idToUserMap} keyed on the user's own uuid and then
 * looks each complaint up by {@code service.getAccountId()}. Hand it a user wearing a v3 id and
 * every lookup misses: {@code setCitizen(null)} on an HTTP 200, a complaint with no citizen, no
 * exception and no log line. {@code upsertUser} then writes that same v3 id back onto
 * {@code accountId}, poisoning the column the map is keyed on. So a v3 answer is relabelled with
 * the 2.x uuid before it reaches any CCRS code, and {@link #requireLegacy} refuses to let an
 * unlabellable one through.
 *
 * <h3>Loud, not lenient</h3>
 * Every method that resolves a subject CCRS must be able to name throws on a miss. Passing the
 * 2.x uuid through unchanged - which is what a translator with nowhere to put a failure has to do -
 * sends v3 an id it has never heard of and gets back a confident answer about nobody. CCRS is the
 * BFF; it has somewhere to put the failure, so it puts it there.
 *
 * <h3>Who may write</h3>
 * A search may never write. {@link #record} takes an explicit {@link IdentitySource} and the two
 * legal ones on the request path are {@code PGR_CREATE} (CCRS itself created the v3 record, so the
 * correspondence is a fact CCRS owns) and {@code DERIVED_FROM_V3} (v3 stated it, in
 * {@code employee_v3.user_id}). A second, different v3 id for a uuid already mapped is a
 * conflict, not an update: it means two v3 records exist for one human and CCRS cannot know which
 * one the existing complaints are about.
 */
@Slf4j
@Component
public class IdentityMapService {

    /** How many unmapped account ids the boot-time reconciliation will name before it stops counting. */
    private static final int ORPHAN_SAMPLE_LIMIT = 50;

    private final IdentityMapRepository repository;
    private final PGRConfiguration config;

    @Autowired
    public IdentityMapService(IdentityMapRepository repository, PGRConfiguration config) {
        this.repository = repository;
        this.config = config;
    }

    // ---------------------------------------------------------------- forward: 2.x uuid -> v3 id

    /** The v3 id for a 2.x uuid, or null when the map does not know it. */
    public String toV3(SubjectKind kind, String legacyUuid) {
        if (isBlank(legacyUuid))
            return null;
        IdentityMapEntry entry = repository.findByLegacyUuid(kind, legacyUuid);
        return entry == null ? null : entry.getV3Id();
    }

    /**
     * The v3 id for a 2.x uuid, or a failure naming it. Use this wherever CCRS is about to ask v3
     * about a specific person: an unmapped uuid sent to v3 unchanged returns an answer about
     * nobody with HTTP 200, and that answer is indistinguishable from "this person has no data".
     */
    public String requireV3(SubjectKind kind, String legacyUuid) {
        String v3Id = toV3(kind, legacyUuid);
        if (v3Id == null)
            throw new CustomException("IDENTITY_MAP_MISS",
                    "No DIGIT 3.0 " + kind.name().toLowerCase() + " is mapped to 2.x uuid " + legacyUuid
                            + ". CCRS cannot ask v3 about a person it cannot name: v3 mints its own ids and "
                            + "ignores a supplied one, and a search on an unknown id answers 200 with an empty "
                            + "result that is indistinguishable from 'this person has no data'. Run the identity "
                            + "migration for this subject, or keep egov.user.api.version=2.x.");
        return v3Id;
    }

    /** Forward lookups in one statement; only the uuids the map knows appear in the result. */
    public Map<String, String> toV3All(SubjectKind kind, Collection<String> legacyUuids) {
        Map<String, String> out = new LinkedHashMap<>();
        repository.findAllByLegacyUuid(kind, legacyUuids)
                .forEach((legacyUuid, entry) -> out.put(legacyUuid, entry.getV3Id()));
        return out;
    }

    // ---------------------------------------------------------------- reverse: v3 id -> 2.x uuid

    /** The 2.x uuid for a v3 id, or null when the map does not know it. */
    public String toLegacy(SubjectKind kind, String v3Id) {
        if (isBlank(v3Id))
            return null;
        IdentityMapEntry entry = repository.findByV3Id(kind, v3Id);
        return entry == null ? null : entry.getLegacyUuid();
    }

    /**
     * The 2.x uuid for a v3 id, or a failure naming it. This is the half that must never degrade:
     * a v3 id that reaches CCRS unlabelled becomes a null citizen on a complaint at HTTP 200 and
     * then overwrites the accountId column with an identifier from the wrong space.
     */
    public String requireLegacy(SubjectKind kind, String v3Id) {
        String legacyUuid = toLegacy(kind, v3Id);
        if (legacyUuid == null)
            throw new CustomException("IDENTITY_MAP_REVERSE_MISS",
                    "DIGIT 3.0 returned " + kind.name().toLowerCase() + " " + v3Id + ", which is in no CCRS "
                            + "identity map row. Letting it through would attach a null citizen to the complaint "
                            + "at HTTP 200 and then write a v3 id into eg_pgr_service_v2.accountid, which is keyed "
                            + "in the 2.x uuid space. Refusing instead.");
        return legacyUuid;
    }

    /** Reverse lookups in one statement; only the ids the map knows appear in the result. */
    public Map<String, String> toLegacyAll(SubjectKind kind, Collection<String> v3Ids) {
        Map<String, String> out = new LinkedHashMap<>();
        repository.findAllByV3Id(kind, v3Ids)
                .forEach((v3Id, entry) -> out.put(v3Id, entry.getLegacyUuid()));
        return out;
    }

    // ---------------------------------------------------------------- writing

    /**
     * Records a correspondence CCRS knows to be true.
     *
     * <p>Idempotent for the same pair: re-recording what is already there is a no-op, so a retried
     * create or a repeated derivation is safe. A DIFFERENT v3 id for a uuid already mapped is a
     * conflict and throws - two v3 records now exist for one human and nothing in this process can
     * know which one the existing complaints are about.
     */
    public IdentityMapEntry record(SubjectKind kind, String legacyUuid, String tenantId, String v3Id,
                                   String v3HumanId, String keycloakSub, IdentitySource source, String recordedBy) {
        if (isBlank(legacyUuid) || isBlank(v3Id) || isBlank(tenantId))
            throw new CustomException("IDENTITY_MAP_INCOMPLETE",
                    "An identity map row needs a 2.x uuid, a tenant and a v3 id; got legacyUuid=" + legacyUuid
                            + " tenantId=" + tenantId + " v3Id=" + v3Id);
        if (source == null)
            throw new CustomException("IDENTITY_MAP_NO_PROVENANCE",
                    "Refusing to record an identity correspondence with no source. A row here decides which "
                            + "human every later answer is about, and a migrated fact and a guess are "
                            + "indistinguishable once written unless the provenance was recorded with it.");
        if (kind == SubjectKind.INDIVIDUAL && source == IdentitySource.DERIVED_FROM_V3)
            throw new CustomException("IDENTITY_MAP_UNDERIVABLE",
                    "An INDIVIDUAL correspondence cannot be derived from v3: the individual registry stores no "
                            + "2.x uuid in any searchable field. Only employee_v3.user_id states one.");

        IdentityMapEntry existing = repository.findByLegacyUuid(kind, legacyUuid);
        if (existing != null) {
            if (v3Id.equals(existing.getV3Id()))
                return existing;
            throw new CustomException("IDENTITY_MAP_CONFLICT",
                    "2.x uuid " + legacyUuid + " is already mapped to " + kind + " " + existing.getV3Id()
                            + " (source " + existing.getSource() + ") and something has just produced "
                            + v3Id + ". Two v3 records exist for one human; CCRS cannot know which one the "
                            + "existing complaints are about. Resolve it in the map, do not overwrite.");
        }

        IdentityMapEntry entry = IdentityMapEntry.builder()
                .subjectKind(kind).legacyUuid(legacyUuid).tenantId(tenantId)
                .v3Id(v3Id).v3HumanId(v3HumanId).keycloakSub(keycloakSub)
                .source(source).createdBy(recordedBy).createdTime(System.currentTimeMillis())
                .build();
        repository.insert(entry);
        log.info("Identity map: recorded {} {} -> {} ({}), source {}", kind, legacyUuid, v3Id, v3HumanId, source);
        return entry;
    }

    // ---------------------------------------------------------------- reconciliation (W27)

    /**
     * The reconciliation instrument, which has to exist BEFORE the map is relied on: after the
     * fact, the evidence needed to rebuild a mapping is exactly the evidence that was lost.
     *
     * <p>Two properties, both necessary and neither sufficient:
     * <ul>
     *   <li><b>total</b> - every account id on a complaint resolves. An unmapped one is a citizen
     *       CCRS can no longer name on DIGIT 3.0.</li>
     *   <li><b>injective</b> - no two 2.x uuids share a v3 id. The unique index makes this
     *       impossible to write, which is not the same as impossible to have: a restore from a
     *       dump taken before the index, or a hand-edited table, can still hold it.</li>
     * </ul>
     */
    public Reconciliation reconcile() {
        List<String> unmapped = repository.findUnmappedComplaintAccountIds(ORPHAN_SAMPLE_LIMIT);
        int individualCollisions = repository.countV3IdsWithMoreThanOneLegacyUuid(SubjectKind.INDIVIDUAL);
        int employeeCollisions = repository.countV3IdsWithMoreThanOneLegacyUuid(SubjectKind.EMPLOYEE);
        return new Reconciliation(unmapped, individualCollisions + employeeCollisions, ORPHAN_SAMPLE_LIMIT);
    }

    /**
     * Runs the reconciliation once at boot, but only on the v3 flag - on 2.x the map is inert and
     * an empty one is the correct state, so reporting it as a problem would be noise that teaches
     * people to ignore this log line.
     *
     * <p>It reports; it does not refuse to start. A deployment that cannot start cannot be rolled
     * back either, and the loudness that actually protects a citizen is per-request:
     * {@link #requireV3} and {@link #requireLegacy} fail the one call that would have answered
     * about the wrong person.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reportAtStartup() {
        if (!config.isUserV3() && !config.isHrmsV3())
            return;
        try {
            Reconciliation reconciliation = reconcile();
            if (reconciliation.isHealthy()) {
                log.info("Identity map reconciliation: no unmapped complaint account ids, no v3-id collisions.");
                return;
            }
            log.error("Identity map reconciliation FAILED. {} Unmapped account ids (first {}): {}. "
                            + "Every complaint named above will fail with IDENTITY_MAP_MISS on the DIGIT 3.0 "
                            + "identity path rather than answering about the wrong person.",
                    reconciliation.summary(), ORPHAN_SAMPLE_LIMIT, reconciliation.getUnmappedAccountIds());
        } catch (Exception e) {
            log.error("Identity map reconciliation could not be run. Treat the map as unverified.", e);
        }
    }

    /** The outcome of {@link #reconcile()}. */
    @Getter
    public static class Reconciliation {

        private final List<String> unmappedAccountIds;
        private final int v3IdCollisions;
        private final int sampleLimit;

        public Reconciliation(List<String> unmappedAccountIds, int v3IdCollisions, int sampleLimit) {
            this.unmappedAccountIds = unmappedAccountIds == null
                    ? Collections.emptyList() : Collections.unmodifiableList(unmappedAccountIds);
            this.v3IdCollisions = v3IdCollisions;
            this.sampleLimit = sampleLimit;
        }

        public boolean isTotal() {
            return unmappedAccountIds.isEmpty();
        }

        public boolean isInjective() {
            return v3IdCollisions == 0;
        }

        public boolean isHealthy() {
            return isTotal() && isInjective();
        }

        public String summary() {
            return "unmapped complaint account ids: " + unmappedAccountIds.size()
                    + (unmappedAccountIds.size() >= sampleLimit ? "+ (sample capped)" : "")
                    + "; v3 ids claimed by more than one 2.x uuid: " + v3IdCollisions + ".";
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
