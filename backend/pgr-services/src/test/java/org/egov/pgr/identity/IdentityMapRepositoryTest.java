package org.egov.pgr.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The SQL this repository produces, asserted where the statement itself carries a decision.
 */
class IdentityMapRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private IdentityMapRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new IdentityMapRepository(jdbcTemplate);
    }

    @Test
    @DisplayName("a bulk lookup is ONE statement with one placeholder per distinct key, not N round trips")
    void bulkLookupIsOneStatement() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(Collections.emptyList());

        repository.findAllByLegacyUuid(SubjectKind.INDIVIDUAL, Arrays.asList("a", "b", "a", null, "  "));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());

        assertTrue(sql.getValue().contains("legacy_uuid IN (?, ?)"), sql.getValue());
        // kind + the two distinct, non-blank keys
        assertEquals(3, args.getValue().length);
        assertEquals("INDIVIDUAL", args.getValue()[0]);
    }

    @Test
    @DisplayName("a bulk lookup of nothing issues no statement at all")
    void emptyBulkLookupIssuesNoStatement() {
        assertTrue(repository.findAllByLegacyUuid(SubjectKind.EMPLOYEE, Collections.emptyList()).isEmpty());
        assertTrue(repository.findAllByV3Id(SubjectKind.EMPLOYEE, null).isEmpty());
        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    @DisplayName("totality is measured against the COMPLAINT table, not against egov-user")
    void totalityIsMeasuredAgainstComplaints() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(Collections.singletonList("orphan"));

        repository.findUnmappedComplaintAccountIds(50);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), eq(String.class), any(Object[].class));
        assertTrue(sql.getValue().contains("eg_pgr_service_v2"), sql.getValue());
        assertTrue(sql.getValue().contains("subject_kind = 'INDIVIDUAL'"), sql.getValue());
    }

    @Test
    @DisplayName("an insert carries the provenance and the timestamp verbatim")
    void insertCarriesProvenance() {
        repository.insert(IdentityMapEntry.builder()
                .subjectKind(SubjectKind.EMPLOYEE).legacyUuid("legacy").tenantId("pg")
                .v3Id("v3").v3HumanId("PGGRO1").keycloakSub(null)
                .source(IdentitySource.DERIVED_FROM_V3).createdBy("sys").createdTime(42L).build());

        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).update(anyString(), args.capture(), args.capture(), args.capture(), args.capture(),
                args.capture(), args.capture(), args.capture(), args.capture(), args.capture());
        assertTrue(args.getAllValues().contains("DERIVED_FROM_V3"));
        assertTrue(args.getAllValues().contains(42L));
    }
}
