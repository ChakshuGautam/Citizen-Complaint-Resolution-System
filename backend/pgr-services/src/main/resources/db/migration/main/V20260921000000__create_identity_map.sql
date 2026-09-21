-- The 2.x-uuid -> DIGIT 3.0 identity map (W14).
--
-- CCRS holds one identifier space of its own: eg_pgr_service_v2.accountid, plus every
-- workflow assignee uuid and every audit createdby/lastmodifiedby value, are 2.x egov-user
-- uuids. DIGIT 3.0 keeps people in two registries that mint their OWN ids and silently
-- ignore a caller-supplied one, and neither of them can be asked "who is 2.x uuid X?" -
-- ?userId= is accepted and answered with the entire tenant. The correspondence therefore
-- has to be somebody's durable property, and the only service that knows which complaint
-- belongs to which human is this one.
--
-- It lives HERE, in the CCRS schema, rather than in the translation layer, for three
-- reasons: the shim is stateless and ends at cutover while this must outlive it; losing
-- the map orphans every complaint ever filed through it, so it has to sit in a database
-- somebody backs up; and the end state (W13) rewrites accountid on eg_pgr_service_v2 from
-- exactly this table, which is one transaction if the two are in one database and a
-- cross-system reconciliation if they are not.
--
-- Written by a one-shot migration job and by CCRS's own individual-create. NEVER by a
-- search: a row here decides which human an answer is about, and a map that learned
-- identities from traffic would hold guesses indistinguishable from migrated facts. The
-- source column keeps that distinction on every row.

CREATE TABLE IF NOT EXISTS eg_pgr_identity_map (
    subject_kind    character varying(16)  NOT NULL,   -- INDIVIDUAL | EMPLOYEE
    legacy_uuid     character varying(128) NOT NULL,   -- the egov-user uuid CCRS holds
    tenantid        character varying(256) NOT NULL,
    v3_id           character varying(128) NOT NULL,   -- individual.id / employee.id
    v3_human_id     character varying(128),            -- individualId (IND-xxxx) / employee code
    keycloak_sub    character varying(128),            -- null until the realm work lands
    source          character varying(32)  NOT NULL,   -- MIGRATION | PGR_CREATE | DERIVED_FROM_V3
    createdby       character varying(128),
    createdtime     bigint                 NOT NULL,
    CONSTRAINT pk_eg_pgr_identity_map PRIMARY KEY (subject_kind, legacy_uuid)
);

-- Injectivity, enforced by the database rather than checked on load. Two 2.x uuids pointing
-- at one v3 person is not a degraded map, it is two humans merged into one, and every
-- answer about either of them afterwards is about the wrong person.
CREATE UNIQUE INDEX IF NOT EXISTS uq_eg_pgr_identity_map_v3
    ON eg_pgr_identity_map (subject_kind, v3_id);

-- The reverse lookup is the hot one: v3 answers arrive wearing v3 ids and have to be
-- relabelled before they reach code that keys on accountId.
CREATE INDEX IF NOT EXISTS idx_eg_pgr_identity_map_tenant
    ON eg_pgr_identity_map (subject_kind, tenantid);
