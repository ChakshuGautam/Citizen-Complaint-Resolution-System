package org.egov.pgr.repository;


import lombok.extern.slf4j.Slf4j;
import org.egov.common.contract.request.RequestInfo;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.util.V3Headers;
import org.egov.pgr.web.models.Idgen.IdGenerationRequest;
import org.egov.pgr.web.models.Idgen.IdGenerationResponse;
import org.egov.pgr.web.models.Idgen.IdRequest;
import org.egov.pgr.web.models.Idgen.IdResponse;
import org.egov.tracer.model.CustomException;
import org.egov.tracer.model.ServiceCallException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@Slf4j
public class IdGenRepository {



    private RestTemplate restTemplate;

    private PGRConfiguration config;

    private ServiceRequestRepository serviceRequestRepository;

    @Autowired
    public IdGenRepository(RestTemplate restTemplate, PGRConfiguration config,
                           ServiceRequestRepository serviceRequestRepository) {
        this.restTemplate = restTemplate;
        this.config = config;
        this.serviceRequestRepository = serviceRequestRepository;
    }


    /**
     * Call iDgen to generateIds
     * @param requestInfo The rquestInfo of the request
     * @param tenantId The tenantiD of the service request
     * @param name Name of the foramt
     * @param format Format of the ids
     * @param count Total Number of idGen ids required
     * @return
     */
    public IdGenerationResponse getId(RequestInfo requestInfo, String tenantId, String name, String format, int count) {

        if (config.isIdgenV3())
            return getIdV3(requestInfo, tenantId, name, format, count);

        List<IdRequest> reqList = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            reqList.add(IdRequest.builder().idName(name).format(format).tenantId(tenantId).build());
        }
        IdGenerationRequest req = IdGenerationRequest.builder().idRequests(reqList).requestInfo(requestInfo).build();
        IdGenerationResponse response = null;
        try {
            response = restTemplate.postForObject( config.getIdGenHost()+ config.getIdGenPath(), req, IdGenerationResponse.class);
        } catch (HttpClientErrorException e) {
            throw new ServiceCallException(e.getResponseBodyAsString());
        } catch (ServiceCallException e) {
            // Carries its text in getError(), and getMessage() is always null on that class, so
            // re-wrapping it below would throw the only diagnosis away. Let it through.
            throw e;
        } catch (Exception e) {
            throw asCustomException(e);
        }
        return response;
    }

    /**
     * The DIGIT 3.0 form of the same call.
     *
     * <p>2.x took a LIST of IdRequests, each carrying its own {@code idName}, {@code tenantId} and
     * a {@code format} pattern, wrapped in a RequestInfo envelope. v3 takes one templateCode and
     * nothing else:
     *
     * <pre>
     *   POST /idgen/v3/generate        X-Tenant-ID: &lt;tenant&gt;
     *   {"templateCode":"pgr.servicerequestid"}
     *   200 {"templateCode":"...","version":"v1","id":"PG-PGR-2026-09-21-900044"}
     * </pre>
     *
     * <p>Note the SINGULAR {@code id}, a bare string, where 2.x answered {@code idResponses[]}.
     * For count &gt; 1 the bulk path answers {@code {"count":n,"ids":[...]}}. Both are folded back
     * into the 2.x IdGenerationResponse the one caller (EnrichmentService.getIdList) already
     * reads, so nothing downstream of here changes.
     *
     * <p>Two 2.x concepts have no v3 home, and neither is faked:
     * <ul>
     *   <li>{@code format} - v3 templates are pre-registered by code and accept no per-request
     *       pattern. 2.x DISCARDED it as well (the live egov-idgen used the MDMS
     *       common-masters.IdFormat entry instead), so the id SHAPE does not change here; what
     *       changes is that the source of truth moves from MDMS to the v3 template store, which is
     *       a data migration and not this commit's business. It is logged when non-blank rather
     *       than dropped in silence.</li>
     *   <li>{@code responseInfo} - v3 returns no envelope at all. The shim synthesises one; there
     *       is no reason to, because nothing in CCRS reads it. It stays null.</li>
     * </ul>
     *
     * <p>The tenant is taken from the idRequest tenant in preference to the caller's own
     * ({@link V3Headers#idgenTenant}): the two legitimately differ - a citizen registered at
     * {@code ke} filing against {@code ke.bomet} - and it is the idRequest tenant whose template
     * must mint the id, exactly as 2.x keyed both the MDMS format and the sequence on it.
     */
    private IdGenerationResponse getIdV3(RequestInfo requestInfo, String tenantId, String name, String format, int count) {

        if (name == null || name.trim().isEmpty())
            throw new CustomException("IDGEN_TEMPLATE_CODE_MISSING",
                    "No idgen template code to generate an id from; v3 /idgen/v3/generate requires templateCode");
        if (count < 1)
            throw new CustomException("IDGEN_COUNT_INVALID",
                    "Asked idgen for " + count + " ids; v3 has no equivalent of generating none");
        if (format != null && !format.trim().isEmpty())
            log.debug("Dropping the idgen format '{}' for template '{}': a v3 template is pre-registered by code and "
                    + "accepts no per-request pattern (2.x discarded it too, in favour of the MDMS IdFormat entry)", format, name);

        boolean bulk = count > 1;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateCode", name);
        if (bulk)
            body.put("count", count);

        String uri = joinUrl(config.getIdgenV3Host(),
                bulk ? config.getIdgenV3GenerateBulkPath() : config.getIdgenV3GeneratePath());
        Map<String, String> headers = V3Headers.forTenant(V3Headers.idgenTenant(requestInfo, tenantId))
                .withUserFrom(requestInfo)
                .build();

        Map<?, ?> response;
        try {
            response = serviceRequestRepository.exchange(HttpMethod.POST, uri, body, headers, Map.class);
        } catch (ServiceCallException e) {
            throw e;
        } catch (Exception e) {
            throw asCustomException(e);
        }

        return IdGenerationResponse.builder().idResponses(idsOf(response)).build();
    }

    /**
     * Reads the ids out of a v3 reply: {@code "id"} on the single path, {@code "ids"} on the bulk
     * one. An answer carrying neither becomes an EMPTY list rather than a null one, so the caller
     * raises its existing "No ids returned from idgen Service" instead of a NullPointerException
     * three frames away from the cause.
     */
    private List<IdResponse> idsOf(Map<?, ?> response) {
        if (response == null)
            return Collections.emptyList();
        Object single = response.get("id");
        if (single instanceof String && !((String) single).trim().isEmpty())
            return Collections.singletonList(IdResponse.builder().id((String) single).build());
        Object bulk = response.get("ids");
        if (bulk instanceof List) {
            List<IdResponse> ids = new ArrayList<>();
            for (Object id : (List<?>) bulk)
                if (id instanceof String && !((String) id).trim().isEmpty())
                    ids.add(IdResponse.builder().id((String) id).build());
            return ids;
        }
        log.error("idgen answered 2xx but carried neither an \"id\" string nor an \"ids\" array: {}", response);
        return Collections.emptyList();
    }

    /**
     * Wraps a failure as a CustomException without assuming it has a cause.
     *
     * <p>This used to read {@code e.getCause().getClass().getName()} unguarded. Against 2.x every
     * failure that reached it was a RestTemplate exception with a cause, so it held; a
     * ServiceCallException from the v3 transport has NONE, and the error path would itself have
     * thrown a NullPointerException, losing the actual diagnosis - which for idgen is usually the
     * 404 "template not found" that tells you the template was never registered.
     */
    private CustomException asCustomException(Exception e) {
        Map<String, String> map = new HashMap<>();
        Throwable cause = e.getCause();
        map.put(cause == null ? e.getClass().getName() : cause.getClass().getName(),
                e.getMessage() == null ? e.toString() : e.getMessage());
        return new CustomException(map);
    }

    private static String joinUrl(String host, String endpoint) {
        if (host.endsWith("/") && endpoint.startsWith("/"))
            return host + endpoint.substring(1);
        if (!host.endsWith("/") && !endpoint.startsWith("/"))
            return host + "/" + endpoint;
        return host + endpoint;
    }

}
