package org.egov.pgr.identity;

import org.egov.pgr.config.PGRConfiguration;
import org.egov.tracer.model.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The map's rules, which are all about refusing rather than answering.
 */
class IdentityMapServiceTest {

    private static final String LEGACY = "f1a94c66-a4b5-46da-9492-07b7db54c69f";
    private static final String V3 = "bf2557f5-7b46-4be7-99a9-848228d41f6d";

    private IdentityMapRepository repository;
    private IdentityMapService service;

    @BeforeEach
    void setUp() {
        repository = mock(IdentityMapRepository.class);
        PGRConfiguration config = new PGRConfiguration();
        config.setUserApiVersion(PGRConfiguration.API_VERSION_V3);
        service = new IdentityMapService(repository, config);
    }

    private IdentityMapEntry entry(String legacy, String v3, IdentitySource source) {
        return IdentityMapEntry.builder()
                .subjectKind(SubjectKind.INDIVIDUAL).legacyUuid(legacy).tenantId("pg")
                .v3Id(v3).v3HumanId("IND-40d04697").source(source).createdTime(1L).build();
    }

    @Test
    @DisplayName("forward: a mapped 2.x uuid resolves to its v3 id")
    void forwardResolves() {
        when(repository.findByLegacyUuid(SubjectKind.INDIVIDUAL, LEGACY))
                .thenReturn(entry(LEGACY, V3, IdentitySource.MIGRATION));
        assertEquals(V3, service.toV3(SubjectKind.INDIVIDUAL, LEGACY));
        assertEquals(V3, service.requireV3(SubjectKind.INDIVIDUAL, LEGACY));
    }

    @Test
    @DisplayName("forward: an unmapped uuid is a loud miss, never passed through to v3 unchanged")
    void forwardMissThrows() {
        when(repository.findByLegacyUuid(SubjectKind.INDIVIDUAL, LEGACY)).thenReturn(null);
        assertNull(service.toV3(SubjectKind.INDIVIDUAL, LEGACY));
        CustomException e = assertThrows(CustomException.class,
                () -> service.requireV3(SubjectKind.INDIVIDUAL, LEGACY));
        assertEquals("IDENTITY_MAP_MISS", e.getCode());
        assertTrue(e.getMessage().contains(LEGACY));
    }

    @Test
    @DisplayName("reverse: a v3 answer is relabelled with the 2.x uuid CCRS already holds")
    void reverseResolves() {
        when(repository.findByV3Id(SubjectKind.INDIVIDUAL, V3))
                .thenReturn(entry(LEGACY, V3, IdentitySource.MIGRATION));
        assertEquals(LEGACY, service.requireLegacy(SubjectKind.INDIVIDUAL, V3));
    }

    @Test
    @DisplayName("reverse: an unlabellable v3 id is refused — this is the half that turns into a null citizen")
    void reverseMissThrows() {
        when(repository.findByV3Id(SubjectKind.INDIVIDUAL, V3)).thenReturn(null);
        CustomException e = assertThrows(CustomException.class,
                () -> service.requireLegacy(SubjectKind.INDIVIDUAL, V3));
        assertEquals("IDENTITY_MAP_REVERSE_MISS", e.getCode());
        assertTrue(e.getMessage().contains("accountid"));
    }

    @Test
    @DisplayName("bulk lookups resolve in one statement and simply omit what is not mapped")
    void bulkLookups() {
        Map<String, IdentityMapEntry> forward = new LinkedHashMap<>();
        forward.put(LEGACY, entry(LEGACY, V3, IdentitySource.MIGRATION));
        when(repository.findAllByLegacyUuid(eq(SubjectKind.INDIVIDUAL), anyCollection())).thenReturn(forward);

        Map<String, String> resolved = service.toV3All(SubjectKind.INDIVIDUAL, Arrays.asList(LEGACY, "unknown"));
        assertEquals(Collections.singletonMap(LEGACY, V3), resolved);
    }

    @Test
    @DisplayName("record: the same pair twice is a no-op, not a second row")
    void recordIsIdempotent() {
        when(repository.findByLegacyUuid(SubjectKind.INDIVIDUAL, LEGACY))
                .thenReturn(entry(LEGACY, V3, IdentitySource.PGR_CREATE));
        service.record(SubjectKind.INDIVIDUAL, LEGACY, "pg", V3, "IND-1", null,
                IdentitySource.PGR_CREATE, "tester");
        verify(repository, never()).insert(any());
    }

    @Test
    @DisplayName("record: a SECOND v3 id for a mapped uuid is a conflict, never an overwrite")
    void recordConflictThrows() {
        when(repository.findByLegacyUuid(SubjectKind.INDIVIDUAL, LEGACY))
                .thenReturn(entry(LEGACY, V3, IdentitySource.MIGRATION));
        CustomException e = assertThrows(CustomException.class,
                () -> service.record(SubjectKind.INDIVIDUAL, LEGACY, "pg", "9999aaaa-0000-0000-0000-000000000000",
                        "IND-2", null, IdentitySource.PGR_CREATE, "tester"));
        assertEquals("IDENTITY_MAP_CONFLICT", e.getCode());
        verify(repository, never()).insert(any());
    }

    @Test
    @DisplayName("record: provenance is mandatory — a row with no source is indistinguishable from a guess")
    void recordWithoutProvenanceThrows() {
        CustomException e = assertThrows(CustomException.class,
                () -> service.record(SubjectKind.INDIVIDUAL, LEGACY, "pg", V3, "IND-1", null, null, "tester"));
        assertEquals("IDENTITY_MAP_NO_PROVENANCE", e.getCode());
        verify(repository, never()).insert(any());
    }

    @Test
    @DisplayName("record: an INDIVIDUAL correspondence cannot claim to have been derived from v3")
    void individualCannotBeDerived() {
        CustomException e = assertThrows(CustomException.class,
                () -> service.record(SubjectKind.INDIVIDUAL, LEGACY, "pg", V3, "IND-1", null,
                        IdentitySource.DERIVED_FROM_V3, "tester"));
        assertEquals("IDENTITY_MAP_UNDERIVABLE", e.getCode());
    }

    @Test
    @DisplayName("record: an EMPLOYEE correspondence MAY be derived — employee_v3.user_id states it")
    void employeeMayBeDerived() {
        when(repository.findByLegacyUuid(SubjectKind.EMPLOYEE, LEGACY)).thenReturn(null);
        service.record(SubjectKind.EMPLOYEE, LEGACY, "pg", V3, "PGGRO1", null,
                IdentitySource.DERIVED_FROM_V3, "tester");
        verify(repository).insert(any());
    }

    @Test
    @DisplayName("reconcile: an unmapped complaint account id makes the map not total")
    void reconcileReportsOrphans() {
        when(repository.findUnmappedComplaintAccountIds(anyInt()))
                .thenReturn(Collections.singletonList("orphan-uuid"));
        when(repository.countV3IdsWithMoreThanOneLegacyUuid(any())).thenReturn(0);

        IdentityMapService.Reconciliation reconciliation = service.reconcile();
        assertTrue(reconciliation.isInjective());
        assertTrue(!reconciliation.isTotal());
        assertTrue(!reconciliation.isHealthy());
        assertTrue(reconciliation.summary().contains("unmapped complaint account ids: 1"));
    }

    @Test
    @DisplayName("reconcile: two 2.x uuids on one v3 id makes the map not injective — two humans merged into one")
    void reconcileReportsCollisions() {
        when(repository.findUnmappedComplaintAccountIds(anyInt())).thenReturn(Collections.emptyList());
        when(repository.countV3IdsWithMoreThanOneLegacyUuid(SubjectKind.INDIVIDUAL)).thenReturn(1);
        when(repository.countV3IdsWithMoreThanOneLegacyUuid(SubjectKind.EMPLOYEE)).thenReturn(0);

        IdentityMapService.Reconciliation reconciliation = service.reconcile();
        assertTrue(reconciliation.isTotal());
        assertTrue(!reconciliation.isInjective());
        assertTrue(!reconciliation.isHealthy());
    }

    @Test
    @DisplayName("startup: the reconciliation is silent on 2.x, where an empty map is the correct state")
    void startupIsSilentOnTwoX() {
        PGRConfiguration twoX = new PGRConfiguration();
        twoX.setUserApiVersion(PGRConfiguration.API_VERSION_2X);
        twoX.setHrmsApiVersion(PGRConfiguration.API_VERSION_2X);
        new IdentityMapService(repository, twoX).reportAtStartup();
        verify(repository, never()).findUnmappedComplaintAccountIds(anyInt());
    }

    @Test
    @DisplayName("startup: on v3 it runs, and a failure to run it does not stop the application")
    void startupRunsOnV3AndSurvivesFailure() {
        when(repository.findUnmappedComplaintAccountIds(anyInt()))
                .thenThrow(new RuntimeException("relation eg_pgr_identity_map does not exist"));
        service.reportAtStartup();
        verify(repository).findUnmappedComplaintAccountIds(anyInt());
    }
}
