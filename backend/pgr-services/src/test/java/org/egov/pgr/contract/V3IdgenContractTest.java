package org.egov.pgr.contract;

import org.egov.pgr.repository.IdGenRepository;
import org.egov.pgr.web.models.Idgen.IdGenerationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Wire contract for the idgen dependency.
 */
class V3IdgenContractTest extends V3ContractTestSupport {

    private IdGenRepository idGenRepository;

    @BeforeEach
    void wireCaller() {
        idGenRepository = new IdGenRepository(restTemplate, config, serviceRequestRepository);
    }

    @Test
    void idgenGenerateIsAPostOfATemplateCodeAlone() {
        server.expect(requestTo("http://localhost:8083/idgen/v3/generate"))
                .andExpect(method(HttpMethod.POST))
                // The id is minted by the TEMPLATE's tenant, not the caller's: 2.x keyed both the
                // MDMS id format and the sequence on the idRequest tenant, and a citizen registered
                // at `ke` legitimately files against `ke.bomet`.
                .andExpect(header("X-Tenant-ID", TENANT))
                // Exactly this body. v3 has no RequestInfo envelope, no idRequests[] list, and no
                // per-request `format` - templates are pre-registered by templateCode, so a format
                // sent here is accepted and discarded.
                .andExpect(content().json("{\"templateCode\":\"pgr.servicerequestid\"}", true))
                .andExpect(content().string(not(containsString("RequestInfo"))))
                .andRespond(withSuccess("{\"templateCode\":\"pgr.servicerequestid\",\"version\":\"v1\",\"id\":\"PG-PGR-2026-09-20-900032\"}",
                        MediaType.APPLICATION_JSON));

        IdGenerationResponse response = idGenRepository.getId(requestInfo(), TENANT,
                "pgr.servicerequestid", "PB-PGR-[cy:yyyy-MM-dd]-[SEQ_EG_PGR_ID]", 1);
        server.verify();

        // C5 made the response half real, so this no longer stops at the request. v3 answers a
        // SINGULAR `id` string where 2.x answered an idResponses[] list, and the one caller
        // (EnrichmentService.getIdList) reads the list - so a translation that produced an empty
        // list here would surface as "IDGEN ERROR: No ids returned", three frames from the cause,
        // on every single complaint creation.
        assertEquals(1, response.getIdResponses().size());
        assertEquals("PG-PGR-2026-09-20-900032", response.getIdResponses().get(0).getId());
    }
}
