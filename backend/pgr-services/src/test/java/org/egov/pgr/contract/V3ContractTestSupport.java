package org.egov.pgr.contract;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.Role;
import org.egov.common.contract.request.User;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;

/**
 * Shared rig for the v3 request-shape contract tests.
 *
 * <p>Every one of them drives a REAL production caller through a REAL
 * {@link ServiceRequestRepository} and a REAL {@link RestTemplate}, with
 * {@link MockRestServiceServer} standing in for the network. That is the whole point: no test in
 * this repository exercises an actual HTTP call, they all stub the transport with a hand-built 2.x
 * object, so the suite asserts CCRS's PARSING of a 2.x envelope and never its PRODUCTION of a
 * request. It can therefore go fully green against a wire contract that is entirely wrong, which is
 * exactly what happened three times during the hybrid exercise - a jurisdiction that expanded to
 * the empty set and returned zero complaints with HTTP 200 for thirty minutes, an employee inbox
 * that was dead for two or more complaints while the happy-path lifecycle stayed green, and an
 * individual registry that passed every health check while being incapable of storing a person.
 *
 * <p>The ObjectMapper below is configured exactly as {@code PGRApp} configures the shared one,
 * because that configuration is the reason a wrong shape is silent here:
 * ACCEPT_CASE_INSENSITIVE_PROPERTIES plus FAIL_ON_UNKNOWN_PROPERTIES=false means a v3 response CCRS
 * does not understand binds to a well-formed model full of nulls and throws nothing. A rig with a
 * stricter mapper would prove something about a service that is not the one being shipped.
 */
abstract class V3ContractTestSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(V3ContractTestSupport.class);

    protected static final String TENANT = "ke.bomet";
    protected static final String STATE_TENANT = "ke";
    protected static final String ACTOR_UUID = "employee-uuid";
    protected static final String AUTH_TOKEN = "gateway-issued-token";

    protected ObjectMapper objectMapper;
    protected RestTemplate restTemplate;
    protected MockRestServiceServer server;
    protected ServiceRequestRepository serviceRequestRepository;
    protected PGRConfiguration config;

    @BeforeEach
    void setUpTransportAndConfig() {
        objectMapper = new ObjectMapper()
                .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        restTemplate = new RestTemplate();
        restTemplate.getMessageConverters().removeIf(converter -> converter instanceof MappingJackson2HttpMessageConverter);
        restTemplate.getMessageConverters().add(new MappingJackson2HttpMessageConverter(objectMapper));
        server = MockRestServiceServer.createServer(restTemplate);

        serviceRequestRepository = new ServiceRequestRepository(objectMapper, restTemplate);

        config = new PGRConfiguration();
        // The 2.x values a deployment has today, so that a premature run of one of these tests
        // reports the path it actually produced rather than a null-concatenated string.
        config.setLocalizationHost("http://localhost:8087");
        config.setLocalizationContextPath("/localization/messages/v1");
        config.setLocalizationSearchEndpoint("/_search");
        config.setIdGenHost("http://localhost:8083/");
        config.setIdGenPath("egov-idgen/id/_generate");
        config.setBoundaryHost("http://localhost:8081");
        config.setBoundarySearchEndpoint("/boundary-service/boundary/_search");
        config.setBoundaryRelationshipSearchEndpoint("/boundary-service/boundary-relationships/_search");
        config.setWfHost("http://localhost:8084");
        config.setWfTransitionPath("/egov-workflow-v2/egov-wf/process/_transition");
        config.setWfBusinessServiceSearchPath("/egov-workflow-v2/egov-wf/businessservice/_search");
        config.setWfProcessInstanceSearchPath("/egov-workflow-v2/egov-wf/process/_search");
        config.setUserHost("http://localhost:8081");
        config.setUserSearchEndpoint("/user/_search");
        config.setUserContextPath("/user/users");
        config.setUserCreateEndpoint("/_createnovalidate");
        config.setUserUpdateEndpoint("/_updatenovalidate");

        // and the v3 side, as a flipped deployment has it.
        config.setLocalizationApiVersion(PGRConfiguration.API_VERSION_V3);
        config.setLocalizationV3Host("http://localhost:8087");
        config.setLocalizationV3SearchEndpoint("/localization/v3/messages");
        config.setIdgenApiVersion(PGRConfiguration.API_VERSION_V3);
        config.setIdgenV3Host("http://localhost:8083");
        config.setIdgenV3GeneratePath("/idgen/v3/generate");
        config.setIdgenV3GenerateBulkPath("/idgen/v3/generate/bulk");
        config.setBoundaryApiVersion(PGRConfiguration.API_VERSION_V3);
        config.setBoundaryV3Host("http://localhost:8081");
        config.setBoundaryV3SearchEndpoint("/boundary/v3/boundaries");
        config.setBoundaryV3RelationshipSearchEndpoint("/boundary/v3/relationship");
        config.setWorkflowApiVersion(PGRConfiguration.API_VERSION_V3);
        config.setWorkflowV3Host("http://localhost:8084");
        config.setWorkflowV3TransitionPath("/workflow/v3/transition");
        config.setWorkflowV3ProcessDefinitionSearchPath("/workflow/v3/process/definition");
        config.setUserApiVersion(PGRConfiguration.API_VERSION_V3);
        config.setUserV3Host("http://localhost:8081");
        config.setUserV3IndividualPath("/individual/v3/individuals");
        config.setV3PageMaxSize(100);
    }

    /** A RequestInfo shaped the way the gateway fills one for an employee acting in {@link #TENANT}. */
    protected RequestInfo requestInfo() {
        Role role = Role.builder().code("PGR_LME").name("LME").tenantId(STATE_TENANT).build();
        User userInfo = User.builder()
                .uuid(ACTOR_UUID)
                .type("EMPLOYEE")
                .tenantId(TENANT)
                .roles(Collections.singletonList(role))
                .build();
        RequestInfo requestInfo = new RequestInfo();
        requestInfo.setApiId("Rainmaker");
        requestInfo.setMsgId("1695000000000|en_IN");
        requestInfo.setUserInfo(userInfo);
        requestInfo.setAuthToken(AUTH_TOKEN);
        return requestInfo;
    }

    /**
     * Runs the production caller and then verifies the request expectations.
     *
     * <p>A caller that cannot map the canned v3 reply throws, and that is not what these tests are
     * about - the response half of each contract belongs to the commit that migrates the
     * dependency and to that dependency's own tests. Only RuntimeException is absorbed: a failed
     * request expectation arrives as an AssertionError and is deliberately allowed through, so a
     * wrong verb, path, header or body still fails the test.
     */
    protected void assertingOnlyTheRequest(Runnable caller) {
        try {
            caller.run();
        } catch (RuntimeException e) {
            LOGGER.info("Caller threw while consuming the canned v3 reply; only the request is under test here: {}", e.toString());
        }
        server.verify();
    }
}
