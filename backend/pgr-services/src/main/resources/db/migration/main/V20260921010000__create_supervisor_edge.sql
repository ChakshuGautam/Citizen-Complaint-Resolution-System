-- The supervisor edge, owned by CCRS (M3).
--
-- CCRS has TWO independent consumers of "who does this employee report to", and on
-- DIGIT 3.0 both of them lose their input at once, because employee_v3 has no supervisor
-- column, no such field in any v3 employee request or response, and zero hits for
-- reportingTo across all sixteen v3 specs:
--
--   EscalationService.resolveNextAssignee  -> hard failure, ESCALATION_TOP_OF_HIERARCHY.
--   VisibilityService (via the projection) -> SILENT WIDENING. With no projected
--       reportees, TEAM falls back to the tenant-wide view, so every supervisor's
--       "my team" inbox quietly becomes "the whole tenant".
--
-- The second one is why this table exists rather than a flag. Disabling escalation does
-- nothing about it: the projection's reporting_to would simply be NULL on every row and
-- the inbox would widen with no error anywhere. The edge has to exist whether or not
-- escalation runs, so CCRS owns it.
--
-- NOT derived from jurisdiction. That was measured and it cannot work: all the lab
-- employees share one jurisdiction across three rungs of the ladder, so a jurisdiction
-- derivation yields a hierarchy CYCLE. employee_jurisdiction_v3 is boundary containment,
-- not an org chart, and access_jbac_rules authorises API paths by extracted jurisdiction
-- with parent_implies_children about the BOUNDARY hierarchy - it cannot express
-- "PGGRO1 reports to PGLME2" and holds zero rows anyway.

CREATE TABLE IF NOT EXISTS eg_pgr_supervisor_edge (
    employee_uuid   character varying(128) NOT NULL,
    tenantid        character varying(256) NOT NULL,
    supervisor_uuid character varying(128) NOT NULL,
    -- MIGRATED_FROM_HRMS: copied from eg_pgr_hrms_projection at cutover, i.e. the edge
    --   2.x HRMS last told CCRS, frozen. ADMIN: maintained in CCRS afterwards.
    source          character varying(32)  NOT NULL,
    createdby       character varying(128),
    createdtime     bigint                 NOT NULL,
    CONSTRAINT pk_eg_pgr_supervisor_edge PRIMARY KEY (employee_uuid),
    -- A self-edge is a cycle of length one and makes the reportee CTE return the
    -- supervisor themselves; refuse it at the schema rather than at one call site.
    CONSTRAINT ck_eg_pgr_supervisor_edge_not_self CHECK (employee_uuid <> supervisor_uuid)
);

CREATE INDEX IF NOT EXISTS idx_eg_pgr_supervisor_edge_supervisor
    ON eg_pgr_supervisor_edge (tenantid, supervisor_uuid);
