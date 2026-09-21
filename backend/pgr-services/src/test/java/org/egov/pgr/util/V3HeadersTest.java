package org.egov.pgr.util;

import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.User;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the per-dependency tenant precedence rules in {@link V3Headers}. Each one is the rule the
 * hybrid shim derived from a live probe against the running v3 containers, and each differs from
 * the others for a reason recorded on the method it implements.
 */
class V3HeadersTest {

    private static RequestInfo requestInfo(String userTenantId, String uuid, String authToken) {
        User userInfo = new User();
        userInfo.setTenantId(userTenantId);
        userInfo.setUuid(uuid);
        RequestInfo requestInfo = new RequestInfo();
        requestInfo.setUserInfo(userInfo);
        requestInfo.setAuthToken(authToken);
        return requestInfo;
    }

    @Test
    void buildsTheTenantAndUserHeaders() {
        Map<String, String> headers = V3Headers
                .forTenant("ke.bomet")
                .withUserFrom(requestInfo("ke", "employee-uuid", null))
                .build();

        assertEquals("ke.bomet", headers.get(V3Headers.TENANT_ID));
        assertEquals("employee-uuid", headers.get(V3Headers.USER_ID));
    }

    @Test
    void aBlankTenantIsNotDefaultedToAnything() {
        assertTrue(V3Headers.forTenant("  ").build().isEmpty());
    }

    @Test
    void forwardsTheCallersBearerTokenAndNeverMintsOne() {
        assertEquals("Bearer abc-123",
                V3Headers.forTenant("ke").withBearerFrom(requestInfo("ke", "u", "abc-123")).build().get(V3Headers.AUTHORIZATION));
        assertEquals("Bearer abc-123",
                V3Headers.forTenant("ke").withBearerFrom(requestInfo("ke", "u", "Bearer abc-123")).build().get(V3Headers.AUTHORIZATION));
        assertFalse(V3Headers.forTenant("ke").withBearerFrom(requestInfo("ke", "u", null)).build()
                .containsKey(V3Headers.AUTHORIZATION));
    }

    @Test
    void idgenPrefersTheIdRequestTenantOverTheCallersOwn() {
        // A citizen registered at `pg` filing against `pg.citya`: 2.x keyed both the id format and
        // the sequence on the idRequest tenant, so that is the tenant whose template must mint.
        assertEquals("pg.citya", V3Headers.idgenTenant(requestInfo("pg", "u", null), "pg.citya"));
        assertEquals("pg", V3Headers.idgenTenant(requestInfo("pg", "u", null), null));
    }

    @Test
    void boundaryPrefersTheQueryTenant() {
        assertEquals("ke.bomet", V3Headers.boundaryTenant(requestInfo("ke", "u", null), "ke.bomet"));
        assertEquals("ke", V3Headers.boundaryTenant(requestInfo("ke", "u", null), ""));
    }

    @Test
    void workflowFallsFromTheQueryToTheProcessInstanceToTheUser() {
        assertEquals("ke.bomet", V3Headers.workflowTenant(requestInfo("ke", "u", null), "ke.bomet", "ke.sotik"));
        assertEquals("ke.sotik", V3Headers.workflowTenant(requestInfo("ke", "u", null), null, "ke.sotik"));
        assertEquals("ke", V3Headers.workflowTenant(requestInfo("ke", "u", null), null, null));
    }

    @Test
    void userFallsFromTheRequestToTheSubjectToTheActingUser() {
        assertEquals("ke", V3Headers.userTenant(requestInfo("ke.bomet", "u", null), "ke", "ke.sotik"));
        assertEquals("ke.sotik", V3Headers.userTenant(requestInfo("ke.bomet", "u", null), null, "ke.sotik"));
        assertEquals("ke.bomet", V3Headers.userTenant(requestInfo("ke.bomet", "u", null), null, null));
    }

    @Test
    void toleratesAnAbsentUserInfo() {
        assertNull(V3Headers.userIdOf(new RequestInfo()));
        assertNull(V3Headers.tenantOfUser(new RequestInfo()));
        assertNull(V3Headers.userIdOf(null));
        assertNull(V3Headers.tenantOfUser(null));
    }
}
