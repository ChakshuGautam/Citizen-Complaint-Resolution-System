package org.egov.pgr.contract;

import org.egov.pgr.policy.BoundaryHierarchyExpander;
import org.egov.pgr.repository.PGRRepository;
import org.egov.pgr.util.HRMSUtil;
import org.egov.pgr.util.MDMSUtils;
import org.egov.pgr.validator.ServiceRequestValidator;
import org.egov.pgr.web.models.Address;
import org.egov.pgr.web.models.Boundary;
import org.egov.pgr.web.models.Service;
import org.egov.pgr.web.models.ServiceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.util.Set;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Wire contract for the two boundary directions.
 */
class V3BoundaryContractTest extends V3ContractTestSupport {

    private ServiceRequestValidator validator;
    private BoundaryHierarchyExpander expander;

    @BeforeEach
    void wireCallers() {
        validator = new ServiceRequestValidator(config, mock(PGRRepository.class), mock(HRMSUtil.class),
                serviceRequestRepository, objectMapper, mock(MDMSUtils.class));
        expander = new BoundaryHierarchyExpander(config, restTemplate, objectMapper);
    }

    private ServiceRequest requestForLocality(String localityCode) {
        return ServiceRequest.builder()
                .requestInfo(requestInfo())
                .service(Service.builder()
                        .tenantId(TENANT)
                        .serviceCode("POTHOLE")
                        .address(Address.builder()
                                .tenantId(TENANT)
                                .locality(Boundary.builder().code(localityCode).build())
                                .build())
                        .build())
                .build();
    }

    @Test
    @Disabled("Enabled by C7 — feat(boundary): move the existence check to GET /boundary/v3/boundaries")
    void boundaryExistenceCheckIsAGetOnCodesWithTheTenantInAHeader() {
        server.expect(requestTo(allOf(
                        startsWith("http://localhost:8081/boundary/v3/boundaries?"),
                        containsString("codes=LOC001"),
                        not(containsString("tenantId=")))))
                .andExpect(method(HttpMethod.GET))
                // 400 "Missing X-Tenant-Id header" without this.
                .andExpect(header("X-Tenant-ID", TENANT))
                // validateBoundary posts a BARE RequestInfo on 2.x, not a wrapper. v3 takes neither.
                .andExpect(content().bytes(new byte[0]))
                .andRespond(withSuccess("{\"boundary\":[{\"id\":\"b1\",\"tenantId\":\"ke.bomet\",\"code\":\"LOC001\",\"additionalAttributes\":null,\"auditDetails\":{\"createdBy\":\"sys\",\"modifiedBy\":\"sys\",\"createdTime\":1695000000000,\"modifiedTime\":1695000000000}}]}",
                        MediaType.APPLICATION_JSON));

        assertingOnlyTheRequest(() -> validator.validateBoundary(requestForLocality("LOC001")));
    }

    @Test
    @Disabled("Enabled by C8 — feat(boundary): move relationship expansion to GET /boundary/v3/relationship")
    void boundaryRelationshipExpansionIsAGetAndReadsTheLowercaseEnvelopeKey() {
        server.expect(requestTo(allOf(
                        startsWith("http://localhost:8081/boundary/v3/relationship?"),
                        containsString("hierarchyType=ADMIN"),
                        containsString("codes=BOMET"),
                        containsString("includeChildren=true"),
                        not(containsString("tenantId=")))))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Tenant-ID", TENANT))
                .andExpect(content().bytes(new byte[0]))
                // v3 spells the envelope key "tenantBoundary"; CCRS looks it up as a literal,
                // case-sensitive JsonNode key. A leaf omits "children" where 2.x emits [].
                .andRespond(withSuccess("{\"tenantBoundary\":[{\"tenantId\":\"ke.bomet\",\"hierarchyType\":\"ADMIN\",\"boundary\":[{\"id\":\"b1\",\"code\":\"BOMET\",\"boundaryType\":\"County\",\"children\":[{\"id\":\"b2\",\"code\":\"BOMET_BOMET_CENTRAL\",\"boundaryType\":\"SubCounty\"}]}]}]}",
                        MediaType.APPLICATION_JSON));

        Set<String> descendants = expander.descendantsOf(requestInfo(), TENANT, "ADMIN", "BOMET");

        // descendantsOf catches everything and falls back to Set.of(code), so a broken translation
        // degrades to the unexpanded root instead of failing - and then caches that for 30 minutes.
        // Asserting the flattened set is the only way this test can tell the two apart.
        assertEquals(Set.of("BOMET", "BOMET_BOMET_CENTRAL"), descendants);
        server.verify();
    }
}
