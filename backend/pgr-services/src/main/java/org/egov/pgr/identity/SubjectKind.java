package org.egov.pgr.identity;

/**
 * Which DIGIT 3.0 registry a 2.x uuid corresponds to.
 *
 * <p>v3 splits what egov-user kept in one table across two services that share nothing: a person
 * is an {@code individual} and an employment record is an {@code employee}, and the employee row
 * carries no name and no roles because the person behind it lives in the other registry. CCRS
 * asks about both through the same 2.x uuid, so the map needs the discriminator - and the
 * invariants are per-kind, which is why injectivity is enforced per (kind, v3 id) and not
 * globally.
 */
public enum SubjectKind {
    INDIVIDUAL,
    EMPLOYEE
}
