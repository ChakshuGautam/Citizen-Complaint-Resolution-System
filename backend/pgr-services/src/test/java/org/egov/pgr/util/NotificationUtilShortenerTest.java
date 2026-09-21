package org.egov.pgr.util;

import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.User;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The link a citizen is sent, on both stacks.
 *
 * <p>Every assertion here is about the PARSED result rather than the request, because the failure
 * this commit exists to prevent is not a failed call. 2.x answers the short URL as plain text and
 * the old code bound the response to String.class; v3 answers a JSON document. Keeping that
 * binding would have put
 *
 * <pre>{"id":"dac1774d-...","shortUrl":"http://.../eqh6kust","auditDetail":{...}}</pre>
 *
 * into an SMS as the complaint link, with no type error, no exception and no 4xx - the fallback
 * only fires on an EMPTY response, and a JSON blob is not empty. A test that asserted the verb,
 * the path and the headers would have passed while that shipped.
 */
class NotificationUtilShortenerTest {

    private static final String LONG_URL = "https://play.google.com/store/apps/details?id=org.egov.citizen";
    private static final String TENANT = "ke.bomet";

    private RestTemplate restTemplate;
    private ServiceRequestRepository serviceRequestRepository;
    private PGRConfiguration config;
    private NotificationUtil notificationUtil;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        serviceRequestRepository = mock(ServiceRequestRepository.class);

        config = new PGRConfiguration();
        config.setUrlShortnerHost("http://localhost:8098");
        config.setUrlShortnerEndpoint("/egov-url-shortening/shortener");
        config.setUrlShortnerV3Host("http://localhost:8110");
        config.setUrlShortnerV3ShortUrlEndpoint("/url-shortener/v3/short-url");

        notificationUtil = new NotificationUtil();
        ReflectionTestUtils.setField(notificationUtil, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(notificationUtil, "serviceRequestRepository", serviceRequestRepository);
        ReflectionTestUtils.setField(notificationUtil, "config", config);
    }

    private RequestInfo requestInfo() {
        RequestInfo requestInfo = new RequestInfo();
        requestInfo.setUserInfo(User.builder().uuid("employee-uuid").tenantId(TENANT).build());
        return requestInfo;
    }

    private void twoXAnswers(String rawBody) {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class))).thenReturn(rawBody);
    }

    private void v3Answers(Map<String, Object> reply) {
        config.setUrlShortnerApiVersion(PGRConfiguration.API_VERSION_V3);
        when(serviceRequestRepository.exchange(any(HttpMethod.class), anyString(), any(), anyMap(), eq(Map.class)))
                .thenReturn(reply);
    }

    // ------------------------------------------------------------------ 2.x

    @Test
    @SuppressWarnings("unchecked")
    void twoXPostsTheUrlAndReturnsThePlainTextAnswer() {
        twoXAnswers("https://short.example/abc123");

        String link = notificationUtil.getShortnerURL(LONG_URL, TENANT, requestInfo());

        assertEquals("https://short.example/abc123", link);
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(restTemplate).postForObject(url.capture(), body.capture(), eq(String.class));
        assertEquals("http://localhost:8098/egov-url-shortening/shortener", url.getValue());
        assertEquals(LONG_URL, ((Map<String, String>) body.getValue()).get("url"));
        verify(serviceRequestRepository, never()).exchange(any(), anyString(), any(), anyMap(), any());
    }

    @Test
    void twoXNeverHandsAJsonDocumentOnAsALink() {
        // The latent half of the bug: this was already reachable on 2.x, because the fallback only
        // fires on an EMPTY response. A shortener behind a proxy that decided to answer JSON would
        // have put the whole document in an SMS.
        twoXAnswers("{\"id\":\"dac1774d\",\"shortUrl\":\"https://short.example/zzz\"}");

        String link = notificationUtil.getShortnerURL(LONG_URL, TENANT, requestInfo());

        assertEquals("https://short.example/zzz", link);
        assertFalse(link.contains("{"), "a JSON document must never reach an SMS as a link");
    }

    @Test
    void twoXAnErrorArrayIsNotALinkAndFallsBackToTheLongUrl() {
        twoXAnswers("[{\"code\":\"NOT_FOUND\",\"message\":\"config not found\"}]");

        assertEquals(LONG_URL, notificationUtil.getShortnerURL(LONG_URL, TENANT, requestInfo()));
    }

    @Test
    void twoXAnEmptyAnswerStillFallsBackToTheLongUrl() {
        twoXAnswers("");
        assertEquals(LONG_URL, notificationUtil.getShortnerURL(LONG_URL, TENANT, requestInfo()));

        twoXAnswers(null);
        assertEquals(LONG_URL, notificationUtil.getShortnerURL(LONG_URL, TENANT, requestInfo()));
    }

    @Test
    void theOneArgumentFormStillWorksOnTwoX() {
        // Kept so that a caller with no tenant in hand still compiles and behaves; v3 cannot serve
        // one, which is why the two NotificationService call sites now pass a tenant.
        twoXAnswers("https://short.example/abc123");
        assertEquals("https://short.example/abc123", notificationUtil.getShortnerURL(LONG_URL));
    }

    // ------------------------------------------------------------------ v3

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void v3ReturnsTheShortUrlFieldAndNotTheWholeDocument() {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("createdBy", "employee-uuid");
        audit.put("createdTime", 1789961677556L);
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("id", "dac1774d-97ee-4bef-b11f-460f45c7e54a");
        reply.put("shortUrl", "http://short.example/url-shortener/eqh6kust");
        reply.put("auditDetail", audit);
        v3Answers(reply);

        String link = notificationUtil.getShortnerURL(LONG_URL, TENANT, requestInfo());

        // THE assertion. Anything else here - the document, its toString, an empty string - is the
        // shipped bug.
        assertEquals("http://short.example/url-shortener/eqh6kust", link);
        assertFalse(link.contains("auditDetail"));

        ArgumentCaptor<HttpMethod> method = ArgumentCaptor.forClass(HttpMethod.class);
        ArgumentCaptor<String> uri = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Map> headers = ArgumentCaptor.forClass(Map.class);
        verify(serviceRequestRepository).exchange(method.capture(), uri.capture(), body.capture(),
                headers.capture(), eq(Map.class));

        assertEquals(HttpMethod.POST, method.getValue());
        assertEquals("http://localhost:8110/url-shortener/v3/short-url", uri.getValue());
        assertEquals(LONG_URL, ((Map<String, String>) body.getValue()).get("url"));
        // Probed live: 400 "X-Tenant-ID header is required" without the first, and
        // 400 MISSING_HEADER ["X-User-ID"] without the second. Both are mandatory on this call.
        assertEquals(TENANT, headers.getValue().get(V3Headers.TENANT_ID));
        assertEquals("employee-uuid", headers.getValue().get(V3Headers.USER_ID));
        verify(restTemplate, never()).postForObject(anyString(), any(), eq(String.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void v3FallsBackToTheActingUsersTenantWhenTheCallerHasNone() {
        v3Answers(new LinkedHashMap<>(Map.of("shortUrl", "http://short.example/x")));

        notificationUtil.getShortnerURL(LONG_URL, null, requestInfo());

        ArgumentCaptor<Map> headers = ArgumentCaptor.forClass(Map.class);
        verify(serviceRequestRepository).exchange(any(HttpMethod.class), anyString(), any(),
                headers.capture(), eq(Map.class));
        assertEquals(TENANT, headers.getValue().get(V3Headers.TENANT_ID));
    }

    @Test
    void v3AnAnswerWithNoShortUrlFallsBackToTheLongUrl() {
        // A long link in an SMS is worse than a short one and far better than a broken one.
        v3Answers(new LinkedHashMap<>(Map.of("id", "dac1774d")));

        assertEquals(LONG_URL, notificationUtil.getShortnerURL(LONG_URL, TENANT, requestInfo()));
    }

    @Test
    void v3AnEmptyBodyFallsBackToTheLongUrl() {
        v3Answers(null);

        assertEquals(LONG_URL, notificationUtil.getShortnerURL(LONG_URL, TENANT, requestInfo()));
    }
}
