package org.egov.pgr.service;

import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.repository.PGRRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Automatic escalation against a DIGIT 3.0 workflow.
 *
 * <p>It cannot work, for two independent reasons, and neither is a mapping problem:
 *
 * <ol>
 *   <li>ESCALATE is a SELF-LOOP in the PGR definition. v3 stores it and answers every attempt
 *       {@code 409 "invalid state transition: no transition"}.</li>
 *   <li>CCRS escalation re-targets the complaint to the current assignee's {@code reportingTo}.
 *       v3's employee registry has no supervisor column — the field does not exist in any v3
 *       employee request or response — and inventing one would route a real citizen's complaint to
 *       the wrong person, silently. That is the one category of fake that has to be refused.</li>
 * </ol>
 *
 * <p>So the sweep is refused as a whole rather than per complaint. Letting it run would mean a 409
 * per eligible complaint on every tick, each caught by the per-complaint handler, and a scan that
 * reports {@code escalated=0} as though nothing were due — a busy no-op that looks like a quiet
 * night.
 */
class EscalationSchedulerV3RefusalTest {

    private PGRConfiguration config;
    private PGRRepository repository;
    private EscalationConfigurationService configurationService;
    private EscalationScheduler scheduler;

    @BeforeEach
    void setUp() {
        config = mock(PGRConfiguration.class);
        repository = mock(PGRRepository.class);
        configurationService = mock(EscalationConfigurationService.class);

        scheduler = new EscalationScheduler(config, repository,
                mock(EscalationService.class), configurationService, mock(PGRService.class));
        // @Value-injected, so a hand-built scheduler has none and bails before it discovers
        // anything. Set it so the 2.x control case actually reaches the sweep.
        setStateLevelTenant("ke");
    }

    private void setStateLevelTenant(String tenantId) {
        try {
            java.lang.reflect.Field field = EscalationScheduler.class.getDeclaredField("stateLevelTenantId");
            field.setAccessible(true);
            field.set(scheduler, tenantId);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("escalation enabled + workflow on v3: the sweep is skipped entirely, not attempted per complaint")
    void theSweepIsRefusedOnV3() {
        when(config.getEscalationEnabled()).thenReturn(Boolean.TRUE);
        when(config.isWorkflowV3()).thenReturn(true);

        scheduler.scanAndEscalate();

        // Nothing is discovered, nothing is scanned, nothing is escalated. The refusal is an ERROR
        // log naming both reasons and the way out (pgr.escalation.enabled=false, or stay on 2.x).
        verify(configurationService, never()).resolveStateTenants(any(), any());
        verify(repository, never()).getComplaintTenantIds(any());
    }

    @Test
    @DisplayName("escalation enabled + workflow on 2.x: the sweep runs exactly as before")
    void theSweepStillRunsOnTwoX() {
        when(config.getEscalationEnabled()).thenReturn(Boolean.TRUE);
        when(config.isWorkflowV3()).thenReturn(false);
        when(configurationService.resolveStateTenants(any(), any())).thenReturn(java.util.List.of());
        when(repository.getComplaintTenantIds(any())).thenReturn(java.util.List.of());

        scheduler.scanAndEscalate();

        verify(configurationService, times(1)).resolveStateTenants(any(), any());
    }

    @Test
    @DisplayName("escalation disabled: the v3 check is never reached, so nothing is logged about it")
    void adisabledSchedulerIsUnaffected() {
        when(config.getEscalationEnabled()).thenReturn(Boolean.FALSE);

        scheduler.scanAndEscalate();

        verify(config, never()).isWorkflowV3();
        verify(configurationService, never()).resolveStateTenants(any(), any());
    }
}
