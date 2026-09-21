package org.egov.pgr.identity;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Storage for {@code eg_pgr_identity_map}. Deliberately thin: every rule about what may be
 * written and what a miss means lives in {@link IdentityMapService}, so that there is exactly one
 * place to read in order to know whether the map can be trusted.
 */
@Slf4j
@Repository
public class IdentityMapRepository {

    private static final String COLUMNS =
            "subject_kind, legacy_uuid, tenantid, v3_id, v3_human_id, keycloak_sub, source, createdby, createdtime";

    private static final String INSERT_SQL =
            "INSERT INTO eg_pgr_identity_map (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String BY_LEGACY_SQL =
            "SELECT " + COLUMNS + " FROM eg_pgr_identity_map WHERE subject_kind = ? AND legacy_uuid = ?";

    private static final String BY_V3_SQL =
            "SELECT " + COLUMNS + " FROM eg_pgr_identity_map WHERE subject_kind = ? AND v3_id = ?";

    private static final String COUNT_DUPLICATE_V3_SQL =
            "SELECT count(*) FROM (SELECT v3_id FROM eg_pgr_identity_map WHERE subject_kind = ? "
                    + "GROUP BY v3_id HAVING count(*) > 1) duplicates";

    /**
     * Complaint account ids with no INDIVIDUAL row. This is the totality half of the W27
     * reconciliation and it is deliberately expressed against the complaint table rather than
     * against egov-user: what has to be total is not "every 2.x user was migrated" but "every
     * complaint CCRS holds still names a human v3 can answer about".
     */
    private static final String ORPHAN_ACCOUNT_IDS_SQL =
            "SELECT DISTINCT s.accountid FROM eg_pgr_service_v2 s "
                    + "WHERE s.accountid IS NOT NULL AND s.accountid <> '' "
                    + "AND NOT EXISTS (SELECT 1 FROM eg_pgr_identity_map m "
                    + "                WHERE m.subject_kind = 'INDIVIDUAL' AND m.legacy_uuid = s.accountid) "
                    + "LIMIT ?";

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public IdentityMapRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<IdentityMapEntry> ROW_MAPPER = (rs, rowNum) -> IdentityMapEntry.builder()
            .subjectKind(SubjectKind.valueOf(rs.getString("subject_kind")))
            .legacyUuid(rs.getString("legacy_uuid"))
            .tenantId(rs.getString("tenantid"))
            .v3Id(rs.getString("v3_id"))
            .v3HumanId(rs.getString("v3_human_id"))
            .keycloakSub(rs.getString("keycloak_sub"))
            .source(IdentitySource.valueOf(rs.getString("source")))
            .createdBy(rs.getString("createdby"))
            .createdTime(rs.getLong("createdtime"))
            .build();

    public void insert(IdentityMapEntry entry) {
        jdbcTemplate.update(INSERT_SQL,
                entry.getSubjectKind().name(), entry.getLegacyUuid(), entry.getTenantId(),
                entry.getV3Id(), entry.getV3HumanId(), entry.getKeycloakSub(),
                entry.getSource().name(), entry.getCreatedBy(), entry.getCreatedTime());
    }

    public IdentityMapEntry findByLegacyUuid(SubjectKind kind, String legacyUuid) {
        List<IdentityMapEntry> rows = jdbcTemplate.query(BY_LEGACY_SQL, ROW_MAPPER, kind.name(), legacyUuid);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public IdentityMapEntry findByV3Id(SubjectKind kind, String v3Id) {
        List<IdentityMapEntry> rows = jdbcTemplate.query(BY_V3_SQL, ROW_MAPPER, kind.name(), v3Id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Bulk forward lookup, in one statement. Absent uuids are simply absent from the result. */
    public Map<String, IdentityMapEntry> findAllByLegacyUuid(SubjectKind kind, Collection<String> legacyUuids) {
        return findAllBy("legacy_uuid", kind, legacyUuids, IdentityMapEntry::getLegacyUuid);
    }

    /** Bulk reverse lookup, in one statement. */
    public Map<String, IdentityMapEntry> findAllByV3Id(SubjectKind kind, Collection<String> v3Ids) {
        return findAllBy("v3_id", kind, v3Ids, IdentityMapEntry::getV3Id);
    }

    private Map<String, IdentityMapEntry> findAllBy(String column, SubjectKind kind, Collection<String> keys,
                                                    java.util.function.Function<IdentityMapEntry, String> keyOf) {
        List<String> distinct = keys == null ? Collections.emptyList()
                : keys.stream().filter(k -> k != null && !k.trim().isEmpty()).distinct().collect(Collectors.toList());
        if (distinct.isEmpty())
            return Collections.emptyMap();

        String placeholders = String.join(", ", Collections.nCopies(distinct.size(), "?"));
        String sql = "SELECT " + COLUMNS + " FROM eg_pgr_identity_map WHERE subject_kind = ? AND "
                + column + " IN (" + placeholders + ")";
        List<Object> args = new ArrayList<>();
        args.add(kind.name());
        args.addAll(distinct);

        Map<String, IdentityMapEntry> byKey = new LinkedHashMap<>();
        for (IdentityMapEntry entry : jdbcTemplate.query(sql, ROW_MAPPER, args.toArray()))
            byKey.put(keyOf.apply(entry), entry);
        return byKey;
    }

    /** Belt and braces over the unique index: a restored or hand-edited table can still be wrong. */
    public int countV3IdsWithMoreThanOneLegacyUuid(SubjectKind kind) {
        Integer count = jdbcTemplate.queryForObject(COUNT_DUPLICATE_V3_SQL, Integer.class, kind.name());
        return count == null ? 0 : count;
    }

    /** Up to {@code limit} complaint account ids that the map cannot resolve. */
    public List<String> findUnmappedComplaintAccountIds(int limit) {
        return jdbcTemplate.queryForList(ORPHAN_ACCOUNT_IDS_SQL, String.class, limit);
    }
}
