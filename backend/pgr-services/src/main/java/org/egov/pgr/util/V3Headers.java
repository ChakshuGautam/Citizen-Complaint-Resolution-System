package org.egov.pgr.util;

import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.User;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the per-request headers a DIGIT 3.0 call needs, for
 * {@link org.egov.pgr.repository.ServiceRequestRepository#exchange}.
 *
 * <p>2.x carried tenancy and identity in the request body, inside RequestInfo. v3 carries them in
 * headers and reads nothing from a body on a read, so every re-pointed call site has to decide
 * which of several tenants in hand is THE tenant. That decision is not obvious and it is not the
 * same for every dependency - each rule below was derived from a live probe against the running v3
 * containers and is implemented identically in the hybrid shim's translators, which is the only
 * place the wire shapes are known to be right. The rules are gathered here so that no caller has
 * to reinvent one, and so that changing one changes it once.
 *
 * <p>One deliberate omission: this class never MINTS an Authorization token. The shim forges an
 * unsigned {"alg":"none"} JWT because the deployed v3 build does not verify signatures; CCRS must
 * not ship that, because a v3 build that does verify kills it instantly and because a service that
 * accepts forged tokens is not a thing to build on. {@link #withBearerFrom(RequestInfo)} forwards
 * the token the gateway already issued and nothing else. Until the identity work lands, the
 * workflow calls that need roles on a Bearer token do not have a role channel.
 *
 * <p>Note for anyone carrying the precedence rules over from the shim: they end in
 * {@code RequestInfo.tenantId}, which does not exist on this codebase's
 * {@link RequestInfo} - the shim reads a raw 2.x JSON body, CCRS holds a typed object whose only
 * tenant is {@code userInfo.tenantId}. The chains below stop there.
 */
public final class V3Headers {

    public static final String TENANT_ID = "X-Tenant-ID";
    public static final String USER_ID = "X-User-ID";
    public static final String AUTHORIZATION = "Authorization";

    private final Map<String, String> headers = new LinkedHashMap<>();

    private V3Headers() {
    }

    /**
     * Starts a header set for the given tenant. A null or blank tenant is not defaulted to
     * anything: v3 answers 400 "Missing X-Tenant-Id header" and that is the correct, loud outcome,
     * far better than a call silently made against a tenant nobody asked for.
     */
    public static V3Headers forTenant(String tenantId) {
        V3Headers v3Headers = new V3Headers();
        return v3Headers.with(TENANT_ID, tenantId);
    }

    /** Adds X-User-ID from the acting user's uuid. v3 wants it on a write and ignores it on a read. */
    public V3Headers withUserFrom(RequestInfo requestInfo) {
        return with(USER_ID, userIdOf(requestInfo));
    }

    /**
     * Forwards the caller's existing bearer token, which is the only channel through which the v3
     * workflow service reads action roles ({@code realm_access.roles}); an X-Roles header is
     * answered 403. This forwards what the gateway issued - it never mints one.
     */
    public V3Headers withBearerFrom(RequestInfo requestInfo) {
        String authToken = requestInfo == null ? null : requestInfo.getAuthToken();
        if (authToken == null || authToken.trim().isEmpty())
            return this;
        return with(AUTHORIZATION, authToken.toLowerCase().startsWith("bearer ") ? authToken : "Bearer " + authToken);
    }

    /** Adds an arbitrary header. A blank name or value is skipped rather than sent empty. */
    public V3Headers with(String name, String value) {
        if (name != null && !name.trim().isEmpty() && value != null && !value.trim().isEmpty())
            headers.put(name, value);
        return this;
    }

    /** The headers, in insertion order. */
    public Map<String, String> build() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    // ------------------------------------------------------------------ per-dependency tenancy

    /**
     * idgen: {@code idRequests[0].tenantId} wins over the caller's own tenant. The two legitimately
     * differ - a citizen registered at {@code pg} filing against {@code pg.citya} - and 2.x keyed
     * both the MDMS id format and the sequence on the idRequest tenant, so that is the tenant whose
     * template must mint the id.
     */
    public static String idgenTenant(RequestInfo requestInfo, String idRequestTenantId) {
        return firstNonBlank(idRequestTenantId, tenantOfUser(requestInfo));
    }

    /** boundary: an explicit query tenant wins, then the acting user's tenant. */
    public static String boundaryTenant(RequestInfo requestInfo, String queryTenantId) {
        return firstNonBlank(queryTenantId, tenantOfUser(requestInfo));
    }

    /** workflow: an explicit query tenant, then the process instance's own tenant, then the user's. */
    public static String workflowTenant(RequestInfo requestInfo, String queryTenantId, String processInstanceTenantId) {
        return firstNonBlank(queryTenantId, processInstanceTenantId, tenantOfUser(requestInfo));
    }

    /**
     * user/individual: the request's tenant, then the subject user's, then the acting user's.
     *
     * <p>Worth knowing before relying on this: CCRS always searches at the STATE-level tenant
     * ({@code UserUtils.getStateLevelTenant}) while v3 resolves an individual at whatever
     * X-Tenant-ID says. The two tenancy models do not line up, and that is a correctness hazard in
     * its own right rather than a mapping detail - see the identity work.
     */
    public static String userTenant(RequestInfo requestInfo, String requestTenantId, String subjectTenantId) {
        return firstNonBlank(requestTenantId, subjectTenantId, tenantOfUser(requestInfo));
    }

    // ------------------------------------------------------------------ helpers

    /** The acting user's uuid, or null. */
    public static String userIdOf(RequestInfo requestInfo) {
        User userInfo = requestInfo == null ? null : requestInfo.getUserInfo();
        return userInfo == null ? null : blankToNull(userInfo.getUuid());
    }

    /** The acting user's tenant, or null. */
    public static String tenantOfUser(RequestInfo requestInfo) {
        User userInfo = requestInfo == null ? null : requestInfo.getUserInfo();
        return userInfo == null ? null : blankToNull(userInfo.getTenantId());
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            String value = blankToNull(candidate);
            if (value != null)
                return value;
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }
}
