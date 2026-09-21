package org.egov.pgr.identity;

/**
 * Where a row in the identity map came from. Kept on every row because the difference between a
 * migrated fact and a guess is the only thing that makes the map trustworthy, and once written
 * the two are indistinguishable unless the provenance was recorded at the time.
 */
public enum IdentitySource {

    /** A one-shot migration job read the 2.x row and created (or found) the v3 record. */
    MIGRATION,

    /** CCRS created the v3 record itself, during a complaint-create for a new citizen. */
    PGR_CREATE,

    /**
     * v3 itself stated the correspondence and CCRS recorded what it was told. Only legal where a
     * v3 record carries the 2.x uuid in a field of its own - {@code employee_v3.user_id} does, and
     * it is the reason the employee half of this map needs no seeding job. Nothing derives an
     * INDIVIDUAL row this way: the individual registry holds no 2.x uuid anywhere searchable.
     */
    DERIVED_FROM_V3
}
