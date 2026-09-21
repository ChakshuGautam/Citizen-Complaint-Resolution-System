package org.egov.pgr.contract;

import org.egov.pgr.repository.IdGenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
        idGenRepository = new IdGenRepository(restTemplate, config);
    }

    @Test
    @Disabled("Enabled by C5 — feat(idgen): move to POST /idgen/v3/generate")
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

        assertingOnlyTheRequest(() -> idGenRepository.getId(requestInfo(), TENANT,
                "pgr.servicerequestid", "PB-PGR-[cy:yyyy-MM-dd]-[SEQ_EG_PGR_ID]", 1));
    }
}
