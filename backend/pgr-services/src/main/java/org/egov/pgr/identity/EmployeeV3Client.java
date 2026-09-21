package org.egov.pgr.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.egov.common.contract.request.RequestInfo;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.egov.pgr.util.V3Headers;
import org.egov.tracer.model.CustomException;
import org.egov.tracer.model.ServiceCallException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The DIGIT 3.0 employee registry ({@code d3-employee}), presented to CCRS in the 2.x HRMS shape.
 *
 * <h3>Why it answers in the 2.x shape</h3>
 * Five callers read this data through JsonPath expressions over
 * {@code $.Employees[*].assignments[*]...} and {@code $.Employees[*].user.name}. Re-pointing each
 * of them at a v3 shape would mean five new parsers and five sets of re-cut fixtures for a data
 * source whose CONTENT barely changed. Answering in the old shape keeps the 2.x branch of every
 * one of those callers byte-identical and testable, and concentrates every honest gap in one file.
 *
 * <h3>Wire facts, measured on the running build (2026-09-21)</h3>
 * <ul>
 *   <li><b>{@code /employee} is NOT on the v3 gateway.</b> Kong lists ten routes - keycloak,
 *       individuals, idgen, filestore, accounts, individual, mdms-v2, registry, workflow, boundary -
 *       and employee is not one of them: {@code GET :8000/employee/v3/employees} is a 404. The one
 *       service CCRS needs in order to name an assignee is unreachable through the gateway that
 *       fronts every other v3 service, so {@code egov.hrms.v3.host} must address it directly.</li>
 *   <li><b>The list endpoint returns a BARE JSON ARRAY</b> - no envelope, no {@code size}, no
 *       {@code page}, no {@code totalCount}. It cannot be paged.</li>
 *   <li><b>{@code ?id=}, {@code ?code=} and {@code ?userId=} are accepted and IGNORED.</b> Each
 *       returned all four lab employees at HTTP 200. Only {@code ?isActive=} is applied. CCRS
 *       therefore applies those filters ITSELF, over the whole list, and never sends one it has
 *       watched fail - a filter that does not filter is the exact shape of this migration's worst
 *       failure mode.</li>
 *   <li><b>The by-id PATH form works and is tenant-scoped</b>: a real id under the wrong
 *       X-Tenant-ID is a 404, not somebody else's employee. That is the fast path whenever the
 *       identity map already knows the v3 id.</li>
 *   <li><b>X-Tenant-ID and X-User-ID are both mandatory</b> (400 without either).</li>
 * </ul>
 *
 * <h3>The map needs no seeding on this side</h3>
 * {@code employee_v3.user_id} HOLDS the 2.x uuid and is returned on every row, so once CCRS has
 * read the list the correspondence is a fact v3 stated rather than one CCRS guessed - recorded
 * with source DERIVED_FROM_V3. The registry simply cannot be ASKED about it, which is why the map
 * is still worth having: it turns the next lookup into one by-id call instead of a tenant sweep.
 */
@Slf4j
@Component
public class EmployeeV3Client {

    private final ServiceRequestRepository serviceRequestRepository;
    private final PGRConfiguration config;
    private final IdentityMapService identityMap;
    private final IndividualV3Client individualV3Client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public EmployeeV3Client(ServiceRequestRepository serviceRequestRepository, PGRConfiguration config,
                            IdentityMapService identityMap, IndividualV3Client individualV3Client) {
        this.serviceRequestRepository = serviceRequestRepository;
        this.config = config;
        this.identityMap = identityMap;
        this.individualV3Client = individualV3Client;
    }

    // ------------------------------------------------------------------ the 2.x-shaped answers

    /** {@code {"Employees":[...]}} for a set of 2.x employee uuids. */
    public JsonNode searchByLegacyUuids(Collection<String> legacyUuids, String tenantId, RequestInfo requestInfo) {
        List<String> wanted = distinct(legacyUuids);
        if (wanted.isEmpty())
            return employeesEnvelope(Collections.emptyList());

        List<JsonNode> found = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (String legacyUuid : wanted) {
            String v3Id = identityMap.toV3(SubjectKind.EMPLOYEE, legacyUuid);
            JsonNode employee = v3Id == null ? null : readByIdOrNull(v3Id, tenantId, requestInfo);
            if (employee == null)
                unresolved.add(legacyUuid);
            else
                found.add(employee);
        }

        if (!unresolved.isEmpty()) {
            Set<String> stillWanted = new LinkedHashSet<>(unresolved);
            found.addAll(sweepTenant(tenantId, requestInfo,
                    row -> stillWanted.contains(text(row, "userId"))));
        }

        return employeesEnvelope(toTwoXEmployees(found, tenantId, requestInfo));
    }

    /** {@code {"Employees":[...]}} for a set of employee codes (2.x {@code ?codes=}). */
    public JsonNode searchByCodes(Collection<String> codes, String tenantId, RequestInfo requestInfo) {
        Set<String> wanted = new LinkedHashSet<>(distinct(codes));
        if (wanted.isEmpty())
            return employeesEnvelope(Collections.emptyList());
        // There is no usable code filter and no code in the identity map, so a code lookup is
        // always a sweep. The cap below is what keeps that honest.
        List<JsonNode> rows = sweepTenant(tenantId, requestInfo, row -> wanted.contains(text(row, "code")));
        return employeesEnvelope(toTwoXEmployees(rows, tenantId, requestInfo));
    }

    /** {@code {"Employees":[...]}} for every employee of a tenant - the projection rebuild sweep. */
    public JsonNode searchAll(String tenantId, RequestInfo requestInfo) {
        return employeesEnvelope(toTwoXEmployees(sweepTenant(tenantId, requestInfo, row -> true),
                tenantId, requestInfo));
    }

    // ------------------------------------------------------------------ transport

    /** Headers for any employee-registry call; both are mandatory and neither is defaulted. */
    public Map<String, String> headers(String tenantId, RequestInfo requestInfo) {
        if (tenantId == null || tenantId.trim().isEmpty())
            throw new CustomException("EMPLOYEE_TENANT_MISSING",
                    "The DIGIT 3.0 employee registry answers 400 without X-Tenant-ID, and it is the only thing "
                            + "scoping a lookup: the by-id path form under the wrong tenant is a 404, which is the "
                            + "behaviour worth preserving.");
        String actorUuid = V3Headers.userIdOf(requestInfo);
        if (actorUuid == null)
            throw new CustomException("EMPLOYEE_ACTOR_MISSING",
                    "The DIGIT 3.0 employee registry requires X-User-ID on a read (measured: 400) and "
                            + "RequestInfo.userInfo.uuid is empty.");
        return V3Headers.forTenant(tenantId).with(V3Headers.USER_ID, actorUuid).build();
    }

    private String employeesUrl() {
        String host = config.getHrmsV3Host();
        String path = config.getHrmsV3EmployeeSearchEndpoint();
        if (host.endsWith("/") && path.startsWith("/"))
            host = host.substring(0, host.length() - 1);
        return host + path;
    }

    private String jurisdictionsUrl() {
        String host = config.getHrmsV3Host();
        String path = config.getHrmsV3JurisdictionSearchEndpoint();
        if (host.endsWith("/") && path.startsWith("/"))
            host = host.substring(0, host.length() - 1);
        return host + path;
    }

    private JsonNode readByIdOrNull(String v3Id, String tenantId, RequestInfo requestInfo) {
        String uri = UriComponentsBuilder.fromHttpUrl(employeesUrl()).pathSegment(v3Id).encode().toUriString();
        try {
            return serviceRequestRepository.exchange(HttpMethod.GET, uri, null,
                    headers(tenantId, requestInfo), JsonNode.class);
        } catch (ServiceCallException e) {
            // A 404 here means the map points at an employee this tenant does not have - a stale
            // or cross-tenant row. Fall back to the sweep rather than reporting "no such employee",
            // and say so: a map that is wrong is worth knowing about.
            log.warn("Mapped employee {} was not readable in tenant {} ({}); falling back to a tenant sweep",
                    v3Id, tenantId, e.getError());
            return null;
        }
    }

    /**
     * Reads the whole tenant and applies the filter here, because v3 will not.
     *
     * <p>Bounded by {@code egov.hrms.v3.max.employees}: the endpoint is unpaged, so without a cap
     * one employee lookup in a large tenant is an unbounded read. Over the cap is a refusal, not a
     * truncation - a truncated sweep answers confidently about the wrong subset.
     */
    private List<JsonNode> sweepTenant(String tenantId, RequestInfo requestInfo, Predicate<JsonNode> keep) {
        JsonNode body = serviceRequestRepository.exchange(HttpMethod.GET, employeesUrl(), null,
                headers(tenantId, requestInfo), JsonNode.class);

        if (body == null || !body.isArray())
            throw new CustomException("EMPLOYEE_RESPONSE_UNREADABLE",
                    "The employee registry answered with something that is not a JSON array (it returns a BARE "
                            + "array, with no envelope). Binding this to an empty employee list would report "
                            + "'no such employee' for a response that was simply not understood.");

        int cap = config.getHrmsV3MaxEmployees() == null ? 5000 : config.getHrmsV3MaxEmployees();
        if (body.size() > cap)
            throw new CustomException("EMPLOYEE_SWEEP_TOO_LARGE",
                    "Tenant " + tenantId + " has " + body.size() + " employees and the v3 list endpoint is "
                            + "unpaged and applies none of its own filters, so CCRS has to read all of them to "
                            + "find one. That is over egov.hrms.v3.max.employees=" + cap + ". Raise the cap "
                            + "deliberately, or ask DIGIT for an employee filter that filters (the same request "
                            + "as M6).");

        List<JsonNode> kept = new ArrayList<>();
        body.forEach(row -> {
            if (keep.test(row))
                kept.add(row);
        });
        return kept;
    }

    // ------------------------------------------------------------------ 2.x shape synthesis

    /**
     * One v3 employment record rendered as a 2.x HRMS Employee.
     *
     * <p>Three things here are synthesis, not translation, and each is labelled in the output so a
     * reader of a payload can tell:
     * <ul>
     *   <li><b>{@code assignments}</b> is a ONE-ELEMENT list built from v3's scalar
     *       {@code department} and {@code designation}. {@code employee_v3} is one row with one
     *       current state: there is no assignment history at all, so there is nothing for an
     *       {@code isCurrentAssignment==false} row to be. Any CCRS behaviour founded on an
     *       INACTIVE assignment therefore has no v3 input, and cannot be rescued here.</li>
     *   <li><b>{@code assignments[0].reportingTo}</b> is null. There is no supervisor column in
     *       {@code employee_v3}, no such field in any v3 employee request or response, and zero
     *       hits for reportingTo across all sixteen v3 specs. Inventing one would route a real
     *       citizen's complaint to the wrong person, silently, which is the one kind of fake that
     *       must be refused. See the escalation commit for what CCRS does instead.</li>
     *   <li><b>{@code user.name}</b> comes from a SECOND call. v3's employee is an employment
     *       record; the person is behind {@code individualId}, in the other registry. A shim
     *       cannot follow that - one request in, one request out - but CCRS can, and does when the
     *       field is present. When it is absent (it is NULL for every employee on the lab stack,
     *       because the individual half of the employee ETL was never written) the name is null
     *       and a WARN says so rather than a placeholder being invented.</li>
     * </ul>
     */
    private List<JsonNode> toTwoXEmployees(List<JsonNode> rows, String tenantId, RequestInfo requestInfo) {
        List<JsonNode> employees = new ArrayList<>();
        for (JsonNode row : rows) {
            String v3Id = text(row, "id");
            String legacyUuid = text(row, "userId");
            String code = text(row, "code");

            if (legacyUuid != null && v3Id != null)
                recordDerivedMapping(legacyUuid, tenantId, v3Id, code);

            ObjectNode employee = objectMapper.createObjectNode();
            employee.put("uuid", legacyUuid);
            employee.put("code", code);
            employee.put("tenantId", tenantId);
            employee.put("isActive", row.path("isActive").asBoolean(true));
            employee.put("employeeStatus", text(row, "status"));
            employee.put("employeeType", text(row, "employeeType"));
            employee.put("dateOfAppointment", text(row, "dateOfAppointment"));

            ObjectNode user = employee.putObject("user");
            user.put("uuid", legacyUuid);
            // 2.x set an employee's userName to their staff code; v3's employee has no userName
            // field but the code is the same string, and it is the only spelling that ever meant
            // anything for an employee.
            user.put("userName", code);
            user.put("name", resolveName(row, tenantId, requestInfo, code));
            user.put("type", "EMPLOYEE");
            user.putArray("roles");
            user.put("active", row.path("isActive").asBoolean(true));

            ArrayNode assignments = employee.putArray("assignments");
            ObjectNode assignment = assignments.addObject();
            assignment.put("isCurrentAssignment", true);
            assignment.put("department", text(row, "department"));
            assignment.put("designation", text(row, "designation"));
            assignment.put("fromDate", text(row, "dateOfAppointment"));
            assignment.putNull("reportingTo");
            // So that nobody reading a payload mistakes the single row for a real history.
            assignment.put("syntheticFromV3ScalarDepartment", true);

            employee.set("jurisdictions", jurisdictionsOf(v3Id, tenantId, requestInfo));
            employees.add(employee);
        }
        return employees;
    }

    private void recordDerivedMapping(String legacyUuid, String tenantId, String v3Id, String code) {
        try {
            identityMap.record(SubjectKind.EMPLOYEE, legacyUuid, tenantId, v3Id, code, null,
                    IdentitySource.DERIVED_FROM_V3, "employee-v3-read");
        } catch (Exception e) {
            // A conflict here is real news - two v3 employees claiming one 2.x uuid - but it must
            // not stop the lookup that is already holding the answer v3 gave.
            log.error("Could not record the employee correspondence {} -> {} stated by v3", legacyUuid, v3Id, e);
        }
    }

    /** The person behind the employment record, when v3 says who that is. */
    private String resolveName(JsonNode row, String tenantId, RequestInfo requestInfo, String code) {
        String individualId = text(row, "individualId");
        if (individualId == null) {
            log.warn("Employee {} in tenant {} has no individualId, so the DIGIT 3.0 registries hold no NAME for "
                    + "them. An employee is an employment record; the person lives in the individual registry. "
                    + "Leaving the name null rather than substituting the staff code.", code, tenantId);
            return null;
        }
        try {
            JsonNode individual = individualV3Client.readById(individualId, tenantId, requestInfo);
            String givenName = text(individual, "givenName");
            String familyName = text(individual, "familyName");
            if (givenName == null)
                return familyName;
            return familyName == null ? givenName : (givenName + " " + familyName);
        } catch (Exception e) {
            log.warn("Could not read individual {} for employee {}", individualId, code, e);
            return null;
        }
    }

    /**
     * An employee's jurisdictions, from the separate flat collection. 2.x nests them on the
     * employee; v3 keeps them in {@code /employee/v3/jurisdictions}, which answers
     * {@code {"jurisdictions":[...]}} and held ZERO rows on the lab stack.
     */
    private ArrayNode jurisdictionsOf(String v3Id, String tenantId, RequestInfo requestInfo) {
        ArrayNode jurisdictions = objectMapper.createArrayNode();
        if (v3Id == null)
            return jurisdictions;
        try {
            String uri = UriComponentsBuilder.fromHttpUrl(jurisdictionsUrl())
                    .queryParam("employeeId", v3Id).encode().toUriString();
            JsonNode body = serviceRequestRepository.exchange(HttpMethod.GET, uri, null,
                    headers(tenantId, requestInfo), JsonNode.class);
            JsonNode rows = body == null ? null : body.get("jurisdictions");
            if (rows != null && rows.isArray())
                rows.forEach(jurisdictions::add);
        } catch (Exception e) {
            log.warn("Could not read jurisdictions for employee {} in tenant {}", v3Id, tenantId, e);
        }
        return jurisdictions;
    }

    private ObjectNode employeesEnvelope(List<JsonNode> employees) {
        ObjectNode envelope = objectMapper.createObjectNode();
        ArrayNode array = envelope.putArray("Employees");
        employees.forEach(array::add);
        return envelope;
    }

    private static List<String> distinct(Collection<String> values) {
        if (values == null)
            return Collections.emptyList();
        return new ArrayList<>(values.stream()
                .filter(value -> value != null && !value.trim().isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull())
            return null;
        String asText = value.asText();
        return asText.isEmpty() ? null : asText;
    }
}
