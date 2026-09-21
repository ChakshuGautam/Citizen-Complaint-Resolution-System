package org.egov.pgr.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.egov.tracer.model.ServiceCallException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Exercises {@link ServiceRequestRepository#exchange} against a real RestTemplate through
 * MockRestServiceServer, so the assertions are about bytes on the wire rather than about a mock's
 * arguments.
 *
 * <p>Two of these are the point of the method existing at all: a GET carries no body (a DIGIT 3.0
 * read has no RequestInfo and a body makes the request malformed), and a failed call THROWS. Its
 * sibling {@code fetchResult} swallows everything but an HttpClientErrorException into a null
 * return, which every caller in this codebase reads as "empty result" - a 5xx or a dead connection
 * silently becoming an empty inbox is the failure shape this whole migration has to avoid.
 */
class ServiceRequestRepositoryExchangeTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private ServiceRequestRepository repository;

    /** Minimal v3 reply shape: {@code POST /idgen/v3/generate} answers a singular id. */
    public static class GeneratedId {
        public String templateCode;
        public String id;
    }

    @BeforeEach
    void setup() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        repository = new ServiceRequestRepository(new ObjectMapper(), restTemplate);
    }

    private static Map<String, String> tenantHeader() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Tenant-ID", "ke.bomet");
        return headers;
    }

    @Test
    void sendsAGetWithTheSuppliedHeadersAndNoBody() {
        Map<String, String> headers = tenantHeader();
        headers.put("X-User-ID", "employee-uuid");
        server.expect(requestTo("http://localhost:8108/localization/v3/messages?locale=en_IN&module=rainmaker-pgr"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Tenant-ID", "ke.bomet"))
                .andExpect(header("X-User-ID", "employee-uuid"))
                .andExpect(headerDoesNotExist("Content-Type"))
                .andExpect(content().bytes(new byte[0]))
                .andRespond(withSuccess("{\"messages\":[]}", MediaType.APPLICATION_JSON));

        repository.exchange(HttpMethod.GET,
                "http://localhost:8108/localization/v3/messages?locale=en_IN&module=rainmaker-pgr",
                null, headers, Map.class);

        server.verify();
    }

    @Test
    void dropsABodySuppliedOnAGet() {
        server.expect(requestTo("http://localhost:8081/boundary/v3/boundaries?codes=BOMET"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(content().bytes(new byte[0]))
                .andRespond(withSuccess("{\"boundary\":[]}", MediaType.APPLICATION_JSON));

        repository.exchange(HttpMethod.GET, "http://localhost:8081/boundary/v3/boundaries?codes=BOMET",
                Map.of("RequestInfo", Map.of("apiId", "Rainmaker")), tenantHeader(), Map.class);

        server.verify();
    }

    @Test
    void sendsAPostWithAJsonBodyAndContentType() {
        server.expect(requestTo("http://localhost:8083/idgen/v3/generate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header("X-Tenant-ID", "ke.bomet"))
                .andExpect(content().json("{\"templateCode\":\"pgr.servicerequestid\"}"))
                .andRespond(withSuccess("{\"templateCode\":\"pgr.servicerequestid\",\"version\":\"v1\",\"id\":\"PG-PGR-2026-09-20-900032\"}",
                        MediaType.APPLICATION_JSON));

        repository.exchange(HttpMethod.POST, "http://localhost:8083/idgen/v3/generate",
                Map.of("templateCode", "pgr.servicerequestid"), tenantHeader(), Map.class);

        server.verify();
    }

    @Test
    void sendsAPutWithAJsonBody() {
        server.expect(requestTo("http://localhost:8081/individual/v3/individuals/629b9122"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"version\":1}"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        repository.exchange(HttpMethod.PUT, "http://localhost:8081/individual/v3/individuals/629b9122",
                Map.of("version", 1), tenantHeader(), Map.class);

        server.verify();
    }

    @Test
    void sendsADeleteWithNoBody() {
        server.expect(requestTo("http://localhost:8081/individual/v3/individuals/629b9122"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(content().bytes(new byte[0]))
                .andRespond(withSuccess());

        assertNull(repository.exchange(HttpMethod.DELETE,
                "http://localhost:8081/individual/v3/individuals/629b9122", null, tenantHeader(), Map.class));

        server.verify();
    }

    @Test
    void bindsTheResponseToTheRequestedType() {
        server.expect(requestTo("http://localhost:8083/idgen/v3/generate"))
                .andRespond(withSuccess("{\"templateCode\":\"pgr.servicerequestid\",\"version\":\"v1\",\"id\":\"PG-PGR-2026-09-20-900032\"}",
                        MediaType.APPLICATION_JSON));

        GeneratedId generated = repository.exchange(HttpMethod.POST, "http://localhost:8083/idgen/v3/generate",
                Map.of("templateCode", "pgr.servicerequestid"), tenantHeader(), GeneratedId.class);

        assertEquals("PG-PGR-2026-09-20-900032", generated.id);
        assertEquals("pgr.servicerequestid", generated.templateCode);
    }

    @Test
    void skipsBlankHeaderNamesAndValues() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Tenant-ID", "ke.bomet");
        headers.put("X-User-ID", "   ");
        headers.put("Authorization", null);
        headers.put("  ", "ignored");

        server.expect(requestTo("http://localhost:8084/workflow/v3/transition?processCode=PGR"))
                .andExpect(header("X-Tenant-ID", "ke.bomet"))
                .andExpect(headerDoesNotExist("X-User-ID"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{\"processInstances\":[]}", MediaType.APPLICATION_JSON));

        repository.exchange(HttpMethod.GET, "http://localhost:8084/workflow/v3/transition?processCode=PGR",
                null, headers, Map.class);

        server.verify();
    }

    @Test
    void throwsOnAServerErrorRatherThanReturningNull() {
        server.expect(requestTo("http://localhost:8084/workflow/v3/transition?processCode=PGR"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        ServiceCallException thrown = assertThrows(ServiceCallException.class,
                () -> repository.exchange(HttpMethod.GET, "http://localhost:8084/workflow/v3/transition?processCode=PGR",
                        null, tenantHeader(), Map.class));

        assertTrue(thrown.getError().contains("500"), thrown.getError());
    }

    @Test
    void rendersTheBareV3ErrorArrayIntoTheThrownMessage() {
        server.expect(requestTo("http://localhost:8084/workflow/v3/transition"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("[{\"code\":\"INVALID_TRANSITION\",\"message\":\"invalid state transition: no transition\",\"description\":\"COMMENT at PENDINGATLME\",\"params\":null}]"));

        ServiceCallException thrown = assertThrows(ServiceCallException.class,
                () -> repository.exchange(HttpMethod.POST, "http://localhost:8084/workflow/v3/transition",
                        Map.of("action", "COMMENT"), tenantHeader(), Map.class));

        assertTrue(thrown.getError().contains("INVALID_TRANSITION"), thrown.getError());
        assertTrue(thrown.getError().contains("invalid state transition: no transition"), thrown.getError());
        assertTrue(thrown.getError().contains("COMMENT at PENDINGATLME"), thrown.getError());
    }

    @Test
    void passesThroughAnErrorBodyThatIsNotTheV3ArrayShape() {
        server.expect(requestTo("http://localhost:8081/boundary/v3/boundaries"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>502 from the proxy</html>"));

        ServiceCallException thrown = assertThrows(ServiceCallException.class,
                () -> repository.exchange(HttpMethod.GET, "http://localhost:8081/boundary/v3/boundaries",
                        null, tenantHeader(), Map.class));

        assertTrue(thrown.getError().contains("502 from the proxy"), thrown.getError());
    }
}
