package org.egov.pgr.identity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One row of {@code eg_pgr_identity_map}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdentityMapEntry {

    private SubjectKind subjectKind;

    /** The egov-user uuid CCRS holds on the complaint, the workflow and the audit columns. */
    private String legacyUuid;

    private String tenantId;

    /** {@code individual.id} or {@code employee.id} - the uuid v3 minted and will answer to. */
    private String v3Id;

    /** {@code individualId} (IND-xxxx) or the employee {@code code}; human-facing, not a key. */
    private String v3HumanId;

    /** The Keycloak subject for this person, or null - no realm exists on the lab stack yet. */
    private String keycloakSub;

    private IdentitySource source;

    private String createdBy;

    private Long createdTime;
}
