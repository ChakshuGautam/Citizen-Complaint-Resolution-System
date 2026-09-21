package org.egov.pgr.identity;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.egov.common.contract.request.RequestInfo;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.egov.pgr.util.V3Headers;
import org.egov.pgr.web.models.User;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The DIGIT 3.0 individual registry, as CCRS talks to it.
 *
 * <h3>Wire shapes, measured on the running build (2026-09-21, Kong :8000, tenant pg)</h3>
 * <pre>
 * GET /individual/v3/individuals?id=&lt;uuid&gt;[&amp;id=&lt;uuid&gt;...][&amp;size=&amp;page=]
 *     headers: X-Tenant-ID (400 without), X-User-ID (400 without)
 * 200 {"totalCount":1,"page":1,"size":20,"hasMore":false,"individuals":[ {...} ]}
 * </pre>
 * Five things here are not in the inventory and each changes the code:
 * <ol>
 *   <li><b>X-User-ID is MANDATORY on the search</b> - {@code 400 [{"code":"VALIDATION_ERROR",
 *       "message":"Missing required header: X-User-ID"}]} without it. The inventory lists it as a
 *       write-only header.</li>
 *   <li><b>The envelope key is {@code individuals}, plural.</b> The C3 contract sketch and the
 *       spec both say {@code individual}. Under CCRS's mapper
 *       (ACCEPT_CASE_INSENSITIVE_PROPERTIES + FAIL_ON_UNKNOWN_PROPERTIES off) the singular spelling
 *       would have bound to an empty list and thrown nothing - the exact silent shape this
 *       migration exists to stop - so the key is read literally and an envelope without it is a
 *       failure, not an empty result.</li>
 *   <li><b>{@code page} is 1-based and {@code page=0} is a 400.</b> The workflow service's is
 *       0-based. They are different services and they do not agree.</li>
 *   <li><b>{@code size} caps at 100</b> ({@code size=101} is a 400) and <b>defaults to 20</b>. 20
 *       rows of a longer answer is a plausible complete answer, so size is always sent.</li>
 *   <li><b>The comma form of {@code id} is refused</b> ({@code id[0] failed uuid validation}),
 *       loudly, which is better than the workflow service's silent {@code totalCount 0}. The
 *       repeated form genuinely ORs.</li>
 * </ol>
 *
 * <h3>Filters CCRS is allowed to send</h3>
 * Only the ones watched to work: {@code id}, {@code individualId}, {@code mobileNumber}. Measured
 * to be accepted and IGNORED, returning every individual in the tenant at HTTP 200:
 * {@code userId}, {@code isActive} (a search for {@code isActive=false} returned an active
 * individual), and any unknown parameter. 2.x carries {@code userType} and {@code active} as
 * filters on every search; neither survives, so both are dropped rather than sent and trusted -
 * an unproven filter is far worse than no filter, because the search silently WIDENS and an
 * employee's record comes back from a citizen-only search.
 */
@Slf4j
@Component
public class IndividualV3Client {

    private static final String ROWS_KEY = "individuals";
    private static final String TOTAL_KEY = "totalCount";

    private final ServiceRequestRepository serviceRequestRepository;
    private final PGRConfiguration config;
    private final IdentityMapService identityMap;

    @Autowired
    public IndividualV3Client(ServiceRequestRepository serviceRequestRepository, PGRConfiguration config,
                              IdentityMapService identityMap) {
        this.serviceRequestRepository = serviceRequestRepository;
        this.config = config;
        this.identityMap = identityMap;
    }

    // ------------------------------------------------------------------ searches

    /**
     * The individuals behind a set of 2.x uuids, answered wearing those same 2.x uuids.
     *
     * <p>Unmapped uuids are named and refused as a set rather than one at a time: a bulk inbox
     * enrichment that is missing three citizens should say which three, once.
     */
    public List<User> searchByLegacyUuids(Collection<String> legacyUuids, String tenantId, RequestInfo requestInfo) {
        List<String> wanted = distinctNonBlank(legacyUuids);
        if (wanted.isEmpty())
            return Collections.emptyList();

        Map<String, String> legacyToV3 = identityMap.toV3All(SubjectKind.INDIVIDUAL, wanted);
        List<String> unmapped = wanted.stream().filter(uuid -> !legacyToV3.containsKey(uuid)).collect(Collectors.toList());
        if (!unmapped.isEmpty())
            throw new CustomException("IDENTITY_MAP_MISS",
                    "No DIGIT 3.0 individual is mapped to these 2.x uuids: " + unmapped + ". CCRS will not send "
                            + "them to v3 unchanged: an unknown id is answered 200 with an empty result that is "
                            + "indistinguishable from 'this person has no data', and the complaint would come back "
                            + "with a null citizen. Run the identity migration for them, or keep "
                            + "egov.user.api.version=2.x.");

        Map<String, String> v3ToLegacy = new LinkedHashMap<>();
        legacyToV3.forEach((legacyUuid, v3Id) -> v3ToLegacy.put(v3Id, legacyUuid));

        List<User> users = new ArrayList<>();
        for (List<String> chunk : chunk(new ArrayList<>(legacyToV3.values()), pageSize())) {
            UriComponentsBuilder builder = baseSearch();
            chunk.forEach(v3Id -> builder.queryParam("id", v3Id));
            for (JsonNode row : fetchAllPages(builder, tenantId, requestInfo, chunk.size()))
                users.add(toUser(row, tenantId, v3ToLegacy));
        }
        return users;
    }

    /**
     * The individuals holding a mobile number. This is the only 2.x search CCRS makes about a
     * person it cannot already name, so every answer has to come back through the reverse map -
     * an individual v3 knows and CCRS does not is refused rather than guessed at.
     */
    public List<User> searchByMobileNumber(String mobileNumber, String tenantId, RequestInfo requestInfo) {
        if (mobileNumber == null || mobileNumber.trim().isEmpty())
            return Collections.emptyList();

        UriComponentsBuilder builder = baseSearch().queryParam("mobileNumber", mobileNumber.trim());
        List<User> users = new ArrayList<>();
        for (JsonNode row : fetchAllPages(builder, tenantId, requestInfo, pageSize() * maxPages()))
            users.add(toUser(row, tenantId, Collections.emptyMap()));
        return users;
    }

    /** One individual by its v3 id, through the by-id path form, or null on a 404. */
    public JsonNode readById(String v3Id, String tenantId, RequestInfo requestInfo) {
        String uri = UriComponentsBuilder.fromHttpUrl(individualsUrl()).pathSegment(v3Id)
                .encode().toUriString();
        return serviceRequestRepository.exchange(HttpMethod.GET, uri, null,
                headers(tenantId, requestInfo), JsonNode.class);
    }

    // ------------------------------------------------------------------ transport

    /** Headers for any individual-registry call. Both are mandatory and neither is defaulted. */
    public Map<String, String> headers(String tenantId, RequestInfo requestInfo) {
        if (tenantId == null || tenantId.trim().isEmpty())
            throw new CustomException("INDIVIDUAL_TENANT_MISSING",
                    "The DIGIT 3.0 individual registry answers 400 without X-Tenant-ID, and defaulting it would "
                            + "resolve a person against a tenant nobody asked about.");
        String actorUuid = V3Headers.userIdOf(requestInfo);
        if (actorUuid == null)
            throw new CustomException("INDIVIDUAL_ACTOR_MISSING",
                    "The DIGIT 3.0 individual registry requires X-User-ID on a SEARCH as well as on a write "
                            + "(measured: 400 'Missing required header: X-User-ID'), and RequestInfo.userInfo.uuid "
                            + "is empty. 2.x carried the actor in the body, so this is a caller that never had to "
                            + "supply one.");
        return V3Headers.forTenant(tenantId).with(V3Headers.USER_ID, actorUuid).build();
    }

    private UriComponentsBuilder baseSearch() {
        return UriComponentsBuilder.fromHttpUrl(individualsUrl());
    }

    public String individualsUrl() {
        String host = config.getUserV3Host();
        String path = config.getUserV3IndividualPath();
        if (host.endsWith("/") && path.startsWith("/"))
            host = host.substring(0, host.length() - 1);
        return host + path;
    }

    /**
     * Walks pages until the rows collected reach v3's own {@code totalCount}. That comparison is
     * the assertion that paging FINISHED: the shim could only notice a shortfall and log it, and
     * a short list here is an inbox missing people with no sign that anything went wrong.
     */
    private List<JsonNode> fetchAllPages(UriComponentsBuilder builder, String tenantId,
                                         RequestInfo requestInfo, int expectedAtMost) {
        Map<String, String> headers = headers(tenantId, requestInfo);
        List<JsonNode> rows = new ArrayList<>();
        Integer totalCount = null;

        for (int page = 1; page <= maxPages(); page++) {
            String uri = builder.cloneBuilder()
                    .replaceQueryParam("size", pageSize())
                    .replaceQueryParam("page", page)
                    .encode().toUriString();

            JsonNode body = serviceRequestRepository.exchange(HttpMethod.GET, uri, null, headers, JsonNode.class);
            JsonNode envelopeRows = body == null ? null : body.get(ROWS_KEY);
            if (envelopeRows == null || !envelopeRows.isArray())
                throw new CustomException("INDIVIDUAL_RESPONSE_UNREADABLE",
                        "The individual registry answered without an '" + ROWS_KEY + "' array. Keys present: "
                                + fieldNames(body) + ". Binding this to an empty user list would report 'no such "
                                + "person' for a response that was simply not understood, so it is a failure.");

            envelopeRows.forEach(rows::add);
            if (totalCount == null && body.hasNonNull(TOTAL_KEY))
                totalCount = body.get(TOTAL_KEY).asInt();

            boolean hasMore = body.path("hasMore").asBoolean(false);
            if (!hasMore || envelopeRows.size() == 0)
                break;
            if (page == maxPages())
                throw new CustomException("INDIVIDUAL_RESULT_TRUNCATED",
                        "Stopped after " + maxPages() + " pages of individuals with more still to come. A short "
                                + "list here is an answer about fewer people than were asked about.");
        }

        if (totalCount != null && rows.size() < totalCount)
            throw new CustomException("INDIVIDUAL_RESULT_TRUNCATED",
                    "The individual registry reported totalCount " + totalCount + " and delivered " + rows.size()
                            + " rows.");
        if (rows.size() > expectedAtMost)
            log.warn("Individual search returned {} rows where at most {} were asked for; v3 accepts and ignores "
                    + "filters it does not implement, so a widened search is a real possibility", rows.size(), expectedAtMost);
        return rows;
    }

    // ------------------------------------------------------------------ mapping

    /**
     * One v3 individual as CCRS's User.
     *
     * <p>The uuid is the whole point of the exercise: it is the 2.x uuid, never v3's id.
     * {@code UserService.enrichUsers} keys its map on it and {@code upsertUser} writes it back onto
     * {@code accountId}; a v3 id in either place is a null citizen at HTTP 200 followed by a
     * poisoned column.
     *
     * <p>Four fields are permanently null on this path and each was measured, not assumed:
     * {@code id} (v3 is a uuid registry, there is no numeric key), {@code type} (the Individual
     * schema has no user type at all), {@code countryCode} (a v3 phone number carries none) and
     * {@code roles} (roles are Keycloak realm roles, not a property of an individual). The last of
     * those is why the assignee on a domain event says "NA" - see the commit that fixes it.
     */
    private User toUser(JsonNode individual, String tenantId, Map<String, String> knownV3ToLegacy) {
        String v3Id = text(individual, "id");
        if (v3Id == null)
            throw new CustomException("INDIVIDUAL_RESPONSE_UNREADABLE",
                    "An individual row carried no id; it cannot be attributed to anybody.");

        String legacyUuid = knownV3ToLegacy.get(v3Id);
        if (legacyUuid == null)
            legacyUuid = identityMap.requireLegacy(SubjectKind.INDIVIDUAL, v3Id);

        return User.builder()
                .uuid(legacyUuid)
                .name(joinName(text(individual, "givenName"), text(individual, "familyName")))
                // 2.x sets userName = mobileNumber for every citizen it creates
                // (UserUtils.addUserDefaultFields); v3 rejects userName as an unknown field on
                // create, so the mobile number is the only thing that spelling ever meant.
                .userName(text(individual, "mobileNumber"))
                .mobileNumber(text(individual, "mobileNumber"))
                // v3 spells it `email`. `emailId`, the 2.x spelling, is rejected on create as an
                // unknown field, so this is a rename and not a case difference: CCRS's
                // case-insensitive mapper would NOT have bridged it.
                .emailId(text(individual, "email"))
                .tenantId(tenantId)
                .active(individual.path("isActive").asBoolean(true))
                .roles(Collections.emptyList())
                .build();
    }

    private static String joinName(String givenName, String familyName) {
        if (givenName == null && familyName == null)
            return null;
        if (givenName == null)
            return familyName;
        if (familyName == null)
            return givenName;
        return (givenName + " " + familyName).trim();
    }

    // ------------------------------------------------------------------ helpers

    private int pageSize() {
        Integer configured = config.getV3PageMaxSize();
        int size = configured == null ? 100 : configured;
        return Math.min(Math.max(size, 1), 100);
    }

    private int maxPages() {
        Integer configured = config.getWorkflowV3MaxPages();
        return configured == null || configured < 1 ? 50 : configured;
    }

    static List<String> distinctNonBlank(Collection<String> values) {
        if (values == null)
            return Collections.emptyList();
        return new ArrayList<>(values.stream()
                .filter(value -> value != null && !value.trim().isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    static <T> List<List<T>> chunk(List<T> values, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int start = 0; start < values.size(); start += size)
            chunks.add(values.subList(start, Math.min(start + size, values.size())));
        return chunks;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull())
            return null;
        String asText = value.asText();
        return asText.isEmpty() ? null : asText;
    }

    private static String fieldNames(JsonNode node) {
        if (node == null)
            return "(no body)";
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names.toString();
    }
}
