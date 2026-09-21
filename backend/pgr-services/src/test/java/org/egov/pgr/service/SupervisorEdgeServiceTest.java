package org.egov.pgr.service;

import org.egov.pgr.repository.SupervisorEdgeRepository;
import org.egov.tracer.model.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The CCRS-owned org chart: what may go in it, and what an empty one means. */
class SupervisorEdgeServiceTest {

    private SupervisorEdgeRepository repository;
    private SupervisorEdgeService service;

    @BeforeEach
    void setUp() {
        repository = mock(SupervisorEdgeRepository.class);
        service = new SupervisorEdgeService(repository);
    }

    @Test
    @DisplayName("the cutover copy takes the edge 2.x HRMS last told CCRS, out of CCRS's own projection")
    void seedFromProjection() {
        when(repository.seedFromProjection("ke", "migration")).thenReturn(2);
        assertEquals(2, service.seedFromProjection("ke", "migration"));
    }

    @Test
    @DisplayName("an admin edge is stamped ADMIN, so a migrated fact and a later correction stay distinguishable")
    void adminEdgeCarriesItsProvenance() {
        service.setSupervisor("emp", "ke", "boss", "admin-user");
        verify(repository).upsert("emp", "ke", "boss", "ADMIN", "admin-user");
    }

    @Test
    @DisplayName("a self-edge is refused — a cycle of length one makes the reportee walk return the supervisor")
    void selfEdgeIsRefused() {
        CustomException e = assertThrows(CustomException.class,
                () -> service.setSupervisor("emp", "ke", "emp", "admin-user"));
        assertEquals("SUPERVISOR_EDGE_SELF", e.getCode());
        verify(repository, never()).upsert(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("an incomplete edge is refused rather than stored half-formed")
    void incompleteEdgeIsRefused() {
        assertEquals("SUPERVISOR_EDGE_INCOMPLETE", assertThrows(CustomException.class,
                () -> service.setSupervisor("emp", "ke", " ", "admin")).getCode());
        assertEquals("SUPERVISOR_EDGE_INCOMPLETE", assertThrows(CustomException.class,
                () -> service.setSupervisor(null, "ke", "boss", "admin")).getCode());
    }

    @Test
    @DisplayName("zero edges means the hierarchy is UNKNOWN, which is not the same as flat")
    void emptyEdgeMeansUnknown() {
        when(repository.countForTenant("ke")).thenReturn(0);
        assertFalse(service.hasEdgeFor("ke"));
        when(repository.countForTenant("ke")).thenReturn(1);
        assertTrue(service.hasEdgeFor("ke"));
    }
}
