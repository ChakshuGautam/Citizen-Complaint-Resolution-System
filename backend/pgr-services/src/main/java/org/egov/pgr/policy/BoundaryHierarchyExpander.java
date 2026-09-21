package org.egov.pgr.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.egov.common.contract.request.RequestInfo;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.egov.pgr.util.V3Headers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Expands a single HRMS-assigned jurisdiction boundary code into itself plus every DESCENDANT
 * boundary code under it, via boundary-service's {@code boundary-relationships/_search}
 * ({@code includeChildren=true}).
 *
 * <p>Exists to make {@code jurisdiction: OWN} cascade: an employee assigned a coarse boundary
 * (e.g. a County) is meant to see every complaint anywhere under it, not only complaints tagged
 * with that exact County-level code — which in practice never happens, since complaints are
 * always addressed at the leaf (Ward) level. Without this expansion, {@link
 * PolicyDrivenScopeResolver}'s downstream exact-match IN-list (both the Tier-1 SQL {@code
 * ads.locality IN (...)} and the generated Tier-2 JsonLogic condition, built from the same
 * resolved {@code jurisdictionCodes}) silently denies every complaint for any employee not
 * assigned the exact leaf boundary. This is the same list the dashboard now reuses (via {@code
 * AnalyticsRowScopeResolver}), so expanding it here — once, at the source — keeps search and
 * analytics giving the same answer, rather than requiring two different matching strategies (an
 * exact match here, a hierarchical segment match in {@code AnalyticsPlanner}) to agree by
 * coincidence.
 *
 * <p>Cached per (tenantId, hierarchyType, code) for {@link #CACHE_TTL_MILLIS} — boundary
 * hierarchies change rarely, and this sits on PGR search's hot path. A lookup FAILURE falls back
 * to the single unexpanded code, not to failing the caller's whole scope resolution: that is
 * strictly MORE restrictive than a successful expansion, so failing this way only degrades
 * toward today's exact-match behavior rather than opening access further — a safe default for an
 * axis-VALUE expansion, unlike a security DECISION (e.g. {@link AccessPolicyRegistry}'s own
 * outage handling), which must fail closed instead.
 */
@Component
@Slf4j
public class BoundaryHierarchyExpander {

    private static final long CACHE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(30);

    /**
     * The envelope key, and the single line that decides whether an employee sees their
     * complaints. 2.x spells it {@code TenantBoundary}, v3 spells it {@code tenantBoundary}, and
     * this is a literal case-sensitive JsonNode lookup - Jackson's
     * ACCEPT_CASE_INSENSITIVE_PROPERTIES, which rescues every other renamed key in this migration,
     * does not apply to {@code JsonNode.get}. Missing it is not hypothetical: it is exactly what
     * expanded a jurisdiction to the empty set and returned zero complaints with HTTP 200, for
     * thirty minutes, during the hybrid exercise.
     */
    private static final String TENANT_BOUNDARY_KEY_2X = "TenantBoundary";
    private static final String TENANT_BOUNDARY_KEY_V3 = "tenantBoundary";

    private final PGRConfiguration config;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;
    private final ServiceRequestRepository serviceRequestRepository;

    private final Cache<String, Set<String>> cache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL_MILLIS, TimeUnit.MILLISECONDS)
            .build();

    @Autowired
    public BoundaryHierarchyExpander(PGRConfiguration config, RestTemplate restTemplate, ObjectMapper mapper,
                                     ServiceRequestRepository serviceRequestRepository) {
        this.config = config;
        this.restTemplate = restTemplate;
        this.mapper = mapper;
        this.serviceRequestRepository = serviceRequestRepository;
    }

    /**
     * Returns {@code code} plus every descendant boundary code under it, per {@code hierarchyType}.
     * Returns an empty set, not a singleton of {@code code}, when any key parameter is null or the
     * code is blank. Callers that need a safe fallback when hierarchy information is absent must
     * add the raw code themselves.
     */
    public Set<String> descendantsOf(RequestInfo requestInfo, String tenantId, String hierarchyType, String code) {
        if (tenantId == null || hierarchyType == null || code == null || code.isBlank())
            return Set.of();
        String cacheKey = tenantId + "|" + hierarchyType + "|" + code;
        try {
            return cache.get(cacheKey, ignored -> {
                Set<String> resolved = fetch(requestInfo, tenantId, hierarchyType, code);
                if (!resolved.isEmpty() && !resolved.contains(code))
                    throw new IllegalStateException("boundary response did not contain requested root code");
                return resolved.isEmpty() ? Set.of(code) : Set.copyOf(resolved);
            });
        } catch (Exception e) {
            log.warn("BoundaryHierarchyExpander: descendant lookup failed for tenantId='{}' hierarchyType='{}' code='{}' — falling back to the single unexpanded code: {}",
                    tenantId, hierarchyType, code, e.toString());
            return Set.of(code);
        }
    }

    private Set<String> fetch(RequestInfo requestInfo, String tenantId, String hierarchyType, String code) {
        boolean v3 = config.isBoundaryV3();
        Object resp = v3
                ? fetchV3(requestInfo, tenantId, hierarchyType, code)
                : fetchV2(requestInfo, tenantId, hierarchyType, code);

        JsonNode root = mapper.convertValue(resp, JsonNode.class);
        String envelopeKey = v3 ? TENANT_BOUNDARY_KEY_V3 : TENANT_BOUNDARY_KEY_2X;
        JsonNode tenantBoundary = root == null ? null : root.get(envelopeKey);

        // An envelope we cannot read is NOT an empty hierarchy, and conflating the two is what
        // makes this failure invisible. Returning an empty set here would hand descendantsOf the
        // unexpanded code, which it would then CACHE FOR THIRTY MINUTES as a successful
        // resolution - so the outage would outlive its own fix. Throwing instead keeps the same
        // safe, strictly-more-restrictive fallback (descendantsOf catches this and returns
        // Set.of(code)) while logging it and leaving nothing in the cache, so the next call after
        // a fix sees the fix. An envelope that IS present and legitimately empty keeps its old
        // behaviour.
        if (tenantBoundary == null || !tenantBoundary.isArray())
            throw new IllegalStateException("boundary relationship response carried no \"" + envelopeKey
                    + "\" array (" + (v3 ? "v3" : "2.x") + " envelope expected); keys present: "
                    + keysOf(root));

        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode tb : tenantBoundary) {
            JsonNode boundaries = tb.get("boundary");
            if (boundaries != null && boundaries.isArray())
                for (JsonNode b : boundaries)
                    collect(b, codes);
        }
        return codes;
    }

    private Object fetchV2(RequestInfo requestInfo, String tenantId, String hierarchyType, String code) {
        String url = UriComponentsBuilder.fromHttpUrl(joinUrl(
                        config.getBoundaryHost(), config.getBoundaryRelationshipSearchEndpoint()))
                .queryParam("tenantId", tenantId)
                .queryParam("hierarchyType", hierarchyType)
                .queryParam("codes", code)
                .queryParam("includeChildren", true)
                .encode()
                .toUriString();
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("RequestInfo", requestInfo);
        return restTemplate.postForObject(url, req, Map.class);
    }

    /**
     * {@code GET /boundary/v3/relationship?hierarchyType=&codes=&includeChildren=true} with the
     * tenant in X-Tenant-ID and no body.
     *
     * <p>Unlike {@link #fetchV2} this one wraps nothing: 2.x posted {@code {"RequestInfo":{...}}}
     * here (while the sibling existence check posted a BARE RequestInfo, an inconsistency worth
     * knowing about), and v3 reads no body on a read at all.
     *
     * <p>The tree shape is otherwise identical and {@code includeChildren} behaves the same way -
     * only the envelope key case differs, plus two harmless additions: v3 attaches
     * {@code auditDetails} to every node where 2.x emits none, and a LEAF OMITS {@code children}
     * where 2.x emits {@code []}. {@link #collect} already guards on that, which is the only
     * reason this is harmless; an unguarded consumer would NPE on every leaf.
     */
    private Object fetchV3(RequestInfo requestInfo, String tenantId, String hierarchyType, String code) {
        String url = UriComponentsBuilder.fromHttpUrl(joinUrl(
                        config.getBoundaryV3Host(), config.getBoundaryV3RelationshipSearchEndpoint()))
                .queryParam("hierarchyType", hierarchyType)
                .queryParam("codes", code)
                .queryParam("includeChildren", true)
                .encode()
                .toUriString();
        return serviceRequestRepository.exchange(HttpMethod.GET, url, null,
                V3Headers.forTenant(V3Headers.boundaryTenant(requestInfo, tenantId)).build(), Map.class);
    }

    private static String joinUrl(String host, String endpoint) {
        if (host.endsWith("/") && endpoint.startsWith("/"))
            return host + endpoint.substring(1);
        if (!host.endsWith("/") && !endpoint.startsWith("/"))
            return host + "/" + endpoint;
        return host + endpoint;
    }

    /** The top-level keys actually present, so a wrong envelope names itself in the log. */
    private static String keysOf(JsonNode root) {
        if (root == null)
            return "none - empty response body";
        Set<String> keys = new LinkedHashSet<>();
        root.fieldNames().forEachRemaining(keys::add);
        return keys.toString();
    }

    private void collect(JsonNode node, Set<String> out) {
        String code = node.path("code").asText(null);
        if (code != null && !code.isEmpty())
            out.add(code);
        JsonNode children = node.get("children");
        if (children != null && children.isArray())
            for (JsonNode c : children)
                collect(c, out);
    }
}
