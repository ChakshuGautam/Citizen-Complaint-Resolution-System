package org.egov.pgr.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * The CCRS-owned supervisor edge ({@code eg_pgr_supervisor_edge}).
 *
 * <p>This is a new organisational-hierarchy feature inside CCRS, not a port. It exists because
 * DIGIT 3.0 has no supervisor edge anywhere and CCRS has two consumers of one - escalation, which
 * fails loudly without it, and inbox visibility, which widens SILENTLY without it.
 */
@Slf4j
@Repository
public class SupervisorEdgeRepository {

    private static final String UPSERT_SQL =
            "INSERT INTO eg_pgr_supervisor_edge (employee_uuid, tenantid, supervisor_uuid, source, createdby, createdtime) "
                    + "VALUES (?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT (employee_uuid) DO UPDATE SET tenantid = EXCLUDED.tenantid, "
                    + "supervisor_uuid = EXCLUDED.supervisor_uuid, source = EXCLUDED.source, "
                    + "createdby = EXCLUDED.createdby, createdtime = EXCLUDED.createdtime";

    private static final String FIND_SQL =
            "SELECT supervisor_uuid FROM eg_pgr_supervisor_edge WHERE employee_uuid = ?";

    // Same state-level-prefix matching as the reportee walk, so a city-tenant complaint
    // resolves against employees registered at the state tenant (the bomet pattern).
    private static final String COUNT_FOR_TENANT_SQL =
            "SELECT count(*) FROM eg_pgr_supervisor_edge "
                    + "WHERE tenantid = ? OR tenantid = split_part(?, '.', 1)";

    /**
     * The one-shot cutover copy: the edge 2.x HRMS last told CCRS, taken from CCRS's own
     * projection. It is a real source with a real provenance - not a guess - and it answers "who
     * maintains it" for the migration itself. Afterwards it is CCRS's to maintain.
     */
    private static final String SEED_FROM_PROJECTION_SQL =
            "INSERT INTO eg_pgr_supervisor_edge (employee_uuid, tenantid, supervisor_uuid, source, createdby, createdtime) "
                    + "SELECT p.uuid, p.tenantid, p.reporting_to, 'MIGRATED_FROM_HRMS', ?, ? "
                    + "FROM eg_pgr_hrms_projection p "
                    + "WHERE p.reporting_to IS NOT NULL AND p.reporting_to <> '' AND p.reporting_to <> p.uuid "
                    + "  AND (p.tenantid = ? OR p.tenantid = split_part(?, '.', 1)) "
                    + "ON CONFLICT (employee_uuid) DO NOTHING";

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public SupervisorEdgeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(String employeeUuid, String tenantId, String supervisorUuid, String source, String by) {
        jdbcTemplate.update(UPSERT_SQL, employeeUuid, tenantId, supervisorUuid, source, by,
                System.currentTimeMillis());
    }

    /** The supervisor of one employee, or null when CCRS has not been told. */
    public String findSupervisorUuid(String employeeUuid) {
        List<String> rows = jdbcTemplate.queryForList(FIND_SQL, String.class, employeeUuid);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** How many edges this tenant has. Zero means the hierarchy is unknown, not flat. */
    public int countForTenant(String tenantId) {
        Integer count = jdbcTemplate.queryForObject(COUNT_FOR_TENANT_SQL, Integer.class, tenantId, tenantId);
        return count == null ? 0 : count;
    }

    /** Copies the 2.x edge out of the projection. Returns the number of rows written. */
    public int seedFromProjection(String tenantId, String by) {
        int written = jdbcTemplate.update(SEED_FROM_PROJECTION_SQL, by, System.currentTimeMillis(),
                tenantId, tenantId);
        log.info("Supervisor edge: seeded {} rows for tenant {} from the HRMS projection", written, tenantId);
        return written;
    }
}
