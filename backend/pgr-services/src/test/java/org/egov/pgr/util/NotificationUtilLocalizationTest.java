package org.egov.pgr.util;

import com.jayway.jsonpath.JsonPath;
import org.egov.common.contract.request.RequestInfo;
import org.egov.common.utils.MultiStateInstanceUtil;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Both localization paths, side by side.
 *
 * <p>{@code egov.localization.api.version} decides which one runs, and the point of testing them
 * together is that the flag has to be able to go back: a deployment that flips to v3 and finds the
 * new stack wrong must be able to set the property to anything else and get the 2.x call it had
 * yesterday, byte for byte. A test that only exercised the v3 branch would not notice the 2.x one
 * rotting.
 */
class NotificationUtilLocalizationTest {

    private static final String TENANT = "ke.bomet";
    private static final String STATE_TENANT = "ke";

    private ServiceRequestRepository serviceRequestRepository;
    private PGRConfiguration config;
    private NotificationUtil notificationUtil;

    @BeforeEach
    void setUp() {
        serviceRequestRepository = mock(ServiceRequestRepository.class);

        MultiStateInstanceUtil centralInstanceUtil = mock(MultiStateInstanceUtil.class);
        when(centralInstanceUtil.getStateLevelTenant(TENANT)).thenReturn(STATE_TENANT);

        config = new PGRConfiguration();
        config.setLocalizationHost("http://localhost:8087");
        config.setLocalizationContextPath("/localization/messages/v1");
        config.setLocalizationSearchEndpoint("/_search");
        config.setLocalizationV3Host("http://localhost:8087");
        config.setLocalizationV3SearchEndpoint("/localization/v3/messages");

        notificationUtil = new NotificationUtil();
        ReflectionTestUtils.setField(notificationUtil, "serviceRequestRepository", serviceRequestRepository);
        ReflectionTestUtils.setField(notificationUtil, "config", config);
        ReflectionTestUtils.setField(notificationUtil, "centralInstanceUtil", centralInstanceUtil);
    }

    private RequestInfo requestInfo() {
        RequestInfo requestInfo = new RequestInfo();
        requestInfo.setMsgId("1695000000000|en_IN");
        return requestInfo;
    }

    /** {@code {"messages":[...]}} — the shape both stacks return, v3's inert {@code uuid} included. */
    private Map<String, Object> messagesEnvelope() {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("uuid", "7b1b");
        message.put("code", "PGR_CITIZEN_CREATE_PENDINGFORASSIGNMENT_SMS_MESSAGE");
        message.put("message", "Your complaint is registered");
        message.put("module", "rainmaker-pgr");
        message.put("locale", "en_IN");
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("messages", messages);
        return envelope;
    }

    @Test
    void twoXIsStillAPostOfTheRequestInfoWithTheTenantOnTheQueryString() {
        when(serviceRequestRepository.fetchResult(any(StringBuilder.class), any()))
                .thenReturn(new LinkedHashMap<>(messagesEnvelope()));

        RequestInfo requestInfo = requestInfo();
        notificationUtil.getLocalizationMessages(TENANT, requestInfo, "rainmaker-pgr");

        ArgumentCaptor<StringBuilder> uri = ArgumentCaptor.forClass(StringBuilder.class);
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(serviceRequestRepository).fetchResult(uri.capture(), body.capture());

        assertEquals("http://localhost:8087/localization/messages/v1/_search"
                + "?locale=en_IN&tenantId=" + STATE_TENANT + "&module=rainmaker-pgr", uri.getValue().toString());
        // 2.x reads tenancy out of the query string AND takes a bare RequestInfo body.
        assertEquals(requestInfo, body.getValue());
        verify(serviceRequestRepository, never()).exchange(any(), anyString(), any(), anyMap(), any());
    }

    @Test
    @SuppressWarnings("rawtypes")
    void v3IsAGetWithNoBodyAndTheTenantInAHeader() {
        config.setLocalizationApiVersion(PGRConfiguration.API_VERSION_V3);
        when(serviceRequestRepository.exchange(any(HttpMethod.class), anyString(), any(), anyMap(), eq(Map.class)))
                .thenReturn(messagesEnvelope());

        notificationUtil.getLocalizationMessages(TENANT, requestInfo(), "rainmaker-pgr");

        ArgumentCaptor<HttpMethod> method = ArgumentCaptor.forClass(HttpMethod.class);
        ArgumentCaptor<String> uri = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Map> headers = ArgumentCaptor.forClass(Map.class);
        verify(serviceRequestRepository).exchange(method.capture(), uri.capture(), body.capture(),
                headers.capture(), eq(Map.class));

        assertEquals(HttpMethod.GET, method.getValue());
        assertTrue(uri.getValue().startsWith("http://localhost:8087/localization/v3/messages?"), uri.getValue());
        assertTrue(uri.getValue().contains("locale=en_IN"), uri.getValue());
        assertTrue(uri.getValue().contains("module=rainmaker-pgr"), uri.getValue());
        // The tenant is a header dimension in v3. Leaving it on the query too would imply the two
        // stacks agree on where tenancy lives; they do not, and a stray tenantId= is accepted and
        // ignored, which is the quietest way to be wrong.
        assertFalse(uri.getValue().contains("tenantId="), uri.getValue());
        assertEquals(STATE_TENANT, headers.getValue().get(V3Headers.TENANT_ID));
        assertNull(body.getValue(), "a v3 read carries no RequestInfo; a body makes it malformed");
        verify(serviceRequestRepository, never()).fetchResult(any(), any());
    }

    @Test
    void v3MessagesStillResolveThroughTheSameJsonPath() {
        config.setLocalizationApiVersion(PGRConfiguration.API_VERSION_V3);
        when(serviceRequestRepository.exchange(any(HttpMethod.class), anyString(), any(), anyMap(), eq(Map.class)))
                .thenReturn(messagesEnvelope());

        String localization = notificationUtil.getLocalizationMessages(TENANT, requestInfo(), "rainmaker-pgr");

        // The only thing that matters downstream: getCustomizedMsg* reads this string with
        // JsonPath. If the v3 envelope ever stopped being {"messages":[...]} the result here would
        // be a well-formed JSON document that resolves to nothing, and every SMS would fall back to
        // a default message with no error anywhere.
        assertEquals("Your complaint is registered", notificationUtil.getCustomizedMsg(
                "CREATE", "PENDINGFORASSIGNMENT", "CITIZEN", localization));
        List<String> viaJsonPath = JsonPath.parse(localization)
                .read("$..messages[?(@.code==\"PGR_CITIZEN_CREATE_PENDINGFORASSIGNMENT_SMS_MESSAGE\")].message");
        assertEquals(List.of("Your complaint is registered"), viaJsonPath);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void v3AnEmptyBodyIsAnEmptyDocumentRatherThanANullPointer() {
        config.setLocalizationApiVersion(PGRConfiguration.API_VERSION_V3);
        when(serviceRequestRepository.exchange(any(HttpMethod.class), anyString(), any(), anyMap(), eq(Map.class)))
                .thenReturn(null);

        String localization = notificationUtil.getLocalizationMessages(TENANT, requestInfo(), "rainmaker-pgr");

        assertEquals("{}", localization);
        assertNull(notificationUtil.getCustomizedMsg("CREATE", "PENDINGFORASSIGNMENT", "CITIZEN", localization));
    }

    @Test
    void anUnsetFlagIsTwoX() {
        // The flag only ever selects v3 on the exact string; a typo must fail safe onto the stack
        // that is running today rather than onto one that may not be deployed at all.
        config.setLocalizationApiVersion("V3 ");
        assertTrue(config.isLocalizationV3());
        config.setLocalizationApiVersion("v3.0");
        assertFalse(config.isLocalizationV3());
        config.setLocalizationApiVersion(null);
        assertFalse(config.isLocalizationV3());

        when(serviceRequestRepository.fetchResult(any(StringBuilder.class), any()))
                .thenReturn(new LinkedHashMap<>(messagesEnvelope()));
        notificationUtil.getLocalizationMessages(TENANT, requestInfo(), "rainmaker-pgr");
        verify(serviceRequestRepository).fetchResult(any(), any());
    }

    @Test
    void theProducerIsNotTouchedByALookup() {
        // Guards the wiring, not the wire: NotificationUtil holds a Kafka producer and a
        // RestTemplate as well, and neither has any business in a localization read on either path.
        Object producer = mock(org.egov.pgr.producer.Producer.class);
        ReflectionTestUtils.setField(notificationUtil, "producer", producer);
        config.setLocalizationApiVersion(PGRConfiguration.API_VERSION_V3);
        when(serviceRequestRepository.exchange(any(HttpMethod.class), anyString(), any(), anyMap(), eq(Map.class)))
                .thenReturn(messagesEnvelope());

        notificationUtil.getLocalizationMessages(TENANT, requestInfo(), "rainmaker-pgr");

        verifyNoInteractions(producer);
    }
}
