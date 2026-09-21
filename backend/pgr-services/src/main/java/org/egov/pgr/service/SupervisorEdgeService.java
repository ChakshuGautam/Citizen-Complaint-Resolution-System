package org.egov.pgr.service;

import lombok.extern.slf4j.Slf4j;
import org.egov.pgr.repository.SupervisorEdgeRepository;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Maintains the CCRS-owned supervisor edge (M3).
 *
 * <p><b>The decision this class embodies.</b> DIGIT 3.0 has no supervisor edge and cannot be made
 * to have one: {@code employee_v3} has no such column, no v3 employee request or response carries
 * the field, and a grep for reportingTo across all sixteen v3 specs returns nothing. The two
 * options were to own the edge or to disable escalation. CCRS OWNS IT, and the deciding argument
 * is not escalation at all - it is that escalation is not the only consumer. {@code
 * VisibilityService} reads the same edge through {@code eg_pgr_hrms_projection}, and with no edge
 * it widens TEAM to the whole tenant, silently. Disabling escalation would have left that half
 * broken and quiet. The edge has to exist whether or not escalation runs.
 *
 * <p><b>Where the first rows come from.</b> {@link #seedFromProjection} copies the edge 2.x HRMS
 * last told CCRS out of CCRS's own projection, at cutover, stamped {@code MIGRATED_FROM_HRMS}.
 * That is a real source with a real provenance rather than a guess, and it answers "who maintains
 * it" for the migration itself. Afterwards it is CCRS's, through {@link #setSupervisor}.
 *
 * <p><b>What is deliberately NOT here.</b> No derivation from jurisdiction. It was measured and it
 * cannot work: all the lab employees share one jurisdiction across three rungs of the ladder, so
 * any jurisdiction-based derivation produces a hierarchy CYCLE. {@code
 * employee_jurisdiction_v3} is boundary containment, not an org chart, and {@code
 * access_jbac_rules} authorises API paths by extracted jurisdiction with {@code
 * parent_implies_children} referring to the BOUNDARY hierarchy - it cannot express "PGGRO1 reports
 * to PGLME2" and holds zero rows regardless.
 *
 * <p><b>And what is still missing.</b> An admin surface. This class is the service half; there is
 * no CCRS UI for an administrator to maintain the chart, and there needs to be one before a tenant
 * whose people move around can keep this accurate. That is named work, not a hidden gap.
 */
@Slf4j
@Service
public class SupervisorEdgeService {

    private final SupervisorEdgeRepository repository;

    @Autowired
    public SupervisorEdgeService(SupervisorEdgeRepository repository) {
        this.repository = repository;
    }

    /** The one-shot cutover copy. Idempotent: an employee already in the edge is left alone. */
    public int seedFromProjection(String tenantId, String by) {
        return repository.seedFromProjection(tenantId, by);
    }

    /**
     * Sets one edge. A self-edge is refused here as well as by the schema check constraint,
     * because a cycle of length one makes the reportee walk return the supervisor themselves and
     * escalation re-target a complaint to the person it is escalating away from.
     */
    public void setSupervisor(String employeeUuid, String tenantId, String supervisorUuid, String by) {
        if (employeeUuid == null || employeeUuid.trim().isEmpty()
                || supervisorUuid == null || supervisorUuid.trim().isEmpty()
                || tenantId == null || tenantId.trim().isEmpty())
            throw new CustomException("SUPERVISOR_EDGE_INCOMPLETE",
                    "A supervisor edge needs an employee, a tenant and a supervisor.");
        if (employeeUuid.equals(supervisorUuid))
            throw new CustomException("SUPERVISOR_EDGE_SELF",
                    "An employee cannot report to themselves: the reportee walk would return the supervisor and "
                            + "escalation would re-target the complaint to the person it is escalating away from.");
        repository.upsert(employeeUuid, tenantId, supervisorUuid, "ADMIN", by);
    }

    /** Whether CCRS holds any org chart at all for a tenant. Zero means unknown, not flat. */
    public boolean hasEdgeFor(String tenantId) {
        return repository.countForTenant(tenantId) > 0;
    }
}
