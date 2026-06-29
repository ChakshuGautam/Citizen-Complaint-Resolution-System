package org.egov.pgr.service.notification;

import org.egov.common.contract.request.RequestInfo;
import org.egov.common.utils.MultiStateInstanceUtil;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.producer.Producer;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.egov.pgr.service.NotificationService;
import org.egov.pgr.service.WorkflowService;
import org.egov.pgr.util.HRMSUtil;
import org.egov.pgr.util.MDMSUtils;
import org.egov.pgr.util.NotificationUtil;
import org.egov.pgr.web.models.AuditDetails;
import org.egov.pgr.web.models.Service;
import org.egov.pgr.web.models.ServiceRequest;
import org.egov.pgr.web.models.User;
import org.egov.pgr.web.models.Workflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end (service-layer) proof of the TRIGGER: a single workflow transition (ASSIGN), processed
 * with the config-driven flag ON, fans out to ONE pre-rendered event per (recipient x channel) on
 * complaints.domain.events — including SMS, WHATSAPP and EMAIL. This is the "drive an action ->
 * notification triggered" assertion; novu-bridge's pass-through tests then prove each event is
 * dispatched to its channel's send (Novu for SMS/EMAIL, Baileys for WHATSAPP).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class NotificationConfigDrivenEmissionTest {

    private static final String TENANT = "ke.bomet";
    private static final String TOPIC = "complaints.domain.events";

    @Mock private PGRConfiguration config;
    @Mock private NotificationUtil notificationUtil;
    @Mock private WorkflowService workflowService;
    @Mock private ServiceRequestRepository serviceRequestRepository;
    @Mock private MDMSUtils mdmsUtils;
    @Mock private HRMSUtil hrmsUtils;
    @Mock private ObjectMapper mapper;
    @Mock private MultiStateInstanceUtil centralInstanceUtil;
    @Mock private NotificationRouter notificationRouter;
    @Mock private TemplateRenderer templateRenderer;
    @Mock private Producer producer;

    @InjectMocks
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        when(config.getNotificationConfigDriven()).thenReturn(true);
        when(config.getNotificationDefaultLocale()).thenReturn("en_IN");
        when(config.getComplaintsDomainEventsTopic()).thenReturn(TOPIC);
        when(config.getMobileDownloadLink()).thenReturn("http://app/download");

        // Routing: CITIZEN over all three channels for ASSIGN -> PENDINGATLME.
        when(notificationRouter.route(eq(TENANT), eq("PGR"), any(), eq("ASSIGN"), eq("PENDINGATLME")))
                .thenReturn(Collections.singletonList(
                        new RoutingMatch(Collections.singletonList("CITIZEN"),
                                Arrays.asList("SMS", "WHATSAPP", "EMAIL"))));

        // Renderer returns a per-channel body so we can assert each channel was rendered+emitted.
        when(templateRenderer.render(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any()))
                .thenAnswer(inv -> "BODY-" + inv.getArgument(4)); // arg 4 = channel

        // Placeholder enrichment helpers (best-effort, all in try/catch in the service).
        when(notificationUtil.getLocalizationMessages(anyString(), any(), anyString())).thenReturn("{}");
        when(notificationUtil.getShortnerURL(anyString())).thenReturn("http://short/x");
    }

    private ServiceRequest assignRequest() {
        User citizen = User.builder()
                .uuid("citizen-uuid").name("Jane Doe")
                .mobileNumber("712345678").countryCode("+254").emailId("jane@example.com")
                .build();
        Service service = Service.builder()
                .tenantId(TENANT)
                .serviceRequestId("PGR-2026-001")
                .applicationStatus("PENDINGATLME")
                .serviceCode("GarbageNeeds")
                .citizen(citizen)
                .auditDetails(AuditDetails.builder().createdTime(1719600000000L).createdBy("citizen-uuid").build())
                .build();
        Workflow workflow = Workflow.builder().action("ASSIGN").assignes(Collections.emptyList()).build();
        return ServiceRequest.builder()
                .requestInfo(new RequestInfo())
                .service(service)
                .workflow(workflow)
                .build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void assignTransition_emitsOneEventPerChannel_smsWhatsappEmail() {
        notificationService.process(assignRequest(), "save-pgr-request");

        ArgumentCaptor<Object> evt = ArgumentCaptor.forClass(Object.class);
        verify(producer, times(3)).push(eq(TENANT), eq(TOPIC), evt.capture());

        List<Object> events = evt.getAllValues();
        // Index by channel
        java.util.Map<String, Map<String, Object>> byChannel = new java.util.HashMap<>();
        for (Object o : events) {
            Map<String, Object> e = (Map<String, Object>) o;
            byChannel.put((String) e.get("channel"), e);
        }

        assertEquals(new java.util.HashSet<>(Arrays.asList("SMS", "WHATSAPP", "EMAIL")),
                byChannel.keySet());

        for (String ch : Arrays.asList("SMS", "WHATSAPP", "EMAIL")) {
            Map<String, Object> e = byChannel.get(ch);
            assertEquals("BODY-" + ch, e.get("renderedBody"));
            assertEquals("COMPLAINTS.WORKFLOW.ASSIGN", e.get("eventName"));
            assertEquals(TENANT + ":citizen-uuid", e.get("subscriberId"));
            assertEquals("PGR-2026-001:ASSIGN:PENDINGATLME:" + TENANT + ":citizen-uuid:" + ch,
                    e.get("transactionId"));
            Map<String, Object> contact = (Map<String, Object>) e.get("contact");
            assertEquals("CITIZEN", contact.get("type"));
            assertEquals("+254712345678", contact.get("phone"));
            assertEquals("jane@example.com", contact.get("email"));
        }
    }

    @Test
    void flagOff_doesNotUseConfigDrivenPath() {
        when(config.getNotificationConfigDriven()).thenReturn(false);
        // Legacy path will bail early (no NOTIFICATION_ENABLE_FOR_STATUS match / missing deps),
        // but crucially it must NOT invoke the config-driven router.
        notificationService.process(assignRequest(), "save-pgr-request");
        verify(notificationRouter, org.mockito.Mockito.never())
                .route(anyString(), anyString(), any(), anyString(), anyString());
    }
}
