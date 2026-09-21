package org.egov.pgr.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.egov.common.contract.request.RequestInfo;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.egov.pgr.util.V3Headers;
import org.egov.pgr.web.models.User;
import org.egov.tracer.model.CustomException;
import org.egov.tracer.model.ServiceCallException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    private static final List<String> GENDERS = Arrays.asList("MALE", "FEMALE", "OTHER");

    private final ServiceRequestRepository serviceRequestRepository;
    private final PGRConfiguration config;
    private final IdentityMapService identityMap;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public IndividualV3Client(ServiceRequestRepository serviceRequestRepository, PGRConfiguration config,
                              IdentityMapService identityMap) {
        this.serviceRequestRepository = serviceRequestRepository;
        this.config = config;
        this.identityMap = identityMap;
    }

    // ------------------------------------------------------------------ writes

    /**
     * Creates the individual behind a new citizen, and records the correspondence.
     *
     * <p><b>CCRS keeps minting 2.x uuids, on purpose.</b> A citizen created here gets a fresh
     * CCRS-side uuid which is what goes on {@code eg_pgr_service_v2.accountid}, and the map holds
     * the pair. The alternative - writing v3's id straight into accountId for new citizens only -
     * leaves the column holding two identifier spaces at once, so nothing downstream could tell
     * which one it was looking at, and the eventual one-way rewrite (W13) would have to be able
     * to. One space, one map, one cutover.
     *
     * <p>The minted uuid is ALSO sent as {@code userId}, which v3 accepts, stores and echoes
     * (measured). It is not a search filter - {@code ?userId=} is answered with the whole tenant -
     * so it does not replace the map, but it does mean the correspondence is recoverable from v3
     * itself if CCRS's table is ever lost, which is the failure this whole design is afraid of.
     */
    public User create(User user, String tenantId, RequestInfo requestInfo) {
        String legacyUuid = user.getUuid() == null || user.getUuid().trim().isEmpty()
                ? UUID.randomUUID().toString() : user.getUuid();

        ObjectNode body = objectMapper.createObjectNode();
        putName(body, user.getName());
        body.put("gender", genderForCreate());
        if (user.getMobileNumber() != null)
            body.put("mobileNumber", user.getMobileNumber());
        if (user.getEmailId() != null)
            body.put("email", user.getEmailId());
        body.put("userId", legacyUuid);
        refuseUnstorableFields(user);

        JsonNode created = serviceRequestRepository.exchange(HttpMethod.POST, individualsUrl(), body,
                headers(tenantId, requestInfo), JsonNode.class);
        String v3Id = text(created, "id");
        if (v3Id == null)
            throw new CustomException("INDIVIDUAL_CREATE_UNREADABLE",
                    "The individual registry accepted the create and answered without an id. Keys present: "
                            + fieldNames(created) + ". CCRS cannot record a correspondence it was not told.");

        identityMap.record(SubjectKind.INDIVIDUAL, legacyUuid, tenantId, v3Id, text(created, "individualId"),
                null, IdentitySource.PGR_CREATE, V3Headers.userIdOf(requestInfo));

        return toUser(created, tenantId, Collections.singletonMap(v3Id, legacyUuid));
    }

    /**
     * Updates an individual with a READ first - the thing the shim could not do.
     *
     * <p>Two independent reasons the read is mandatory, both measured:
     * <ul>
     *   <li><b>The version.</b> v3's PUT is optimistic-concurrency: no {@code version} is
     *       {@code 409 ROW_VERSION_MISMATCH {"expected":1,"provided":0}}, and a stale one is the
     *       same 409. Only a prior read supplies it. This is where being the BFF is strictly
     *       better than being a shim, which is one-request-in and one-request-out.</li>
     *   <li><b>The PUT is a FULL REPLACE.</b> Measured: re-PUTting a record without its
     *       {@code email} field CLEARED the email, at HTTP 200. 2.x's {@code _updatenovalidate}
     *       merged. So a PUT built from what CCRS happens to hold would silently delete every
     *       field CCRS does not model - starting with gender, which CCRS has no field for at all
     *       and which is mandatory. The read is overlaid, not replaced.</li>
     * </ul>
     */
    public User update(User user, String tenantId, RequestInfo requestInfo) {
        refuseUnstorableFields(user);
        String v3Id = identityMap.requireV3(SubjectKind.INDIVIDUAL, user.getUuid());
        int attempts = 1 + Math.max(0, config.getUserV3IndividualUpdateRetries() == null
                ? 0 : config.getUserV3IndividualUpdateRetries());

        ServiceCallException lastConflict = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            JsonNode current = readById(v3Id, tenantId, requestInfo);
            if (current == null || !current.hasNonNull("version"))
                throw new CustomException("INDIVIDUAL_VERSION_UNREADABLE",
                        "Read individual " + v3Id + " to update it and got no version back (keys: "
                                + fieldNames(current) + "). A PUT without one is refused 409 by v3, and guessing "
                                + "a version is guessing which write wins.");

            ObjectNode body = current.deepCopy();
            if (user.getName() != null)
                putName(body, user.getName());
            if (user.getMobileNumber() != null)
                body.put("mobileNumber", user.getMobileNumber());
            if (user.getEmailId() != null)
                body.put("email", user.getEmailId());
            if (user.getActive() != null)
                body.put("isActive", user.getActive());

            String uri = UriComponentsBuilder.fromHttpUrl(individualsUrl()).pathSegment(v3Id).encode().toUriString();
            try {
                JsonNode updated = serviceRequestRepository.exchange(HttpMethod.PUT, uri, body,
                        headers(tenantId, requestInfo), JsonNode.class);
                return toUser(updated, tenantId, Collections.singletonMap(v3Id, user.getUuid()));
            } catch (ServiceCallException e) {
                if (!isRowVersionMismatch(e))
                    throw e;
                lastConflict = e;
                log.warn("Individual {} changed under us (attempt {} of {}); re-reading the version", v3Id, attempt, attempts);
            }
        }
        throw new CustomException("INDIVIDUAL_UPDATE_CONFLICT",
                "Individual " + v3Id + " was modified by another writer on every one of " + attempts
                        + " attempts. v3 reported: " + (lastConflict == null ? "(none)" : lastConflict.getError())
                        + ". Refusing rather than overwriting a change CCRS never read.");
    }

    /**
     * v3 reports an optimistic-concurrency failure as a bare array whose code is
     * ROW_VERSION_MISMATCH; ServiceCallException carries that text in getError() and its
     * getMessage() is always null.
     */
    private static boolean isRowVersionMismatch(ServiceCallException e) {
        String error = e.getError();
        return error != null && error.contains("ROW_VERSION_MISMATCH");
    }

    /**
     * v3's gender is mandatory on create, is exactly MALE/FEMALE/OTHER (there is no
     * "not disclosed" value), and CCRS's User model has no gender field, so there is nothing true
     * to send. The default is therefore to REFUSE, not to pick one.
     */
    private String genderForCreate() {
        String configured = config.getUserV3IndividualDefaultGender();
        if (configured == null || configured.trim().isEmpty())
            throw new CustomException("INDIVIDUAL_GENDER_REQUIRED",
                    "The DIGIT 3.0 individual registry rejects a create without a gender (400 'gender is required') "
                            + "and accepts only MALE, FEMALE or OTHER - there is no 'not disclosed' value. CCRS's "
                            + "user model carries no gender at all, so CCRS has nothing true to send and will not "
                            + "invent one. Collect gender at registration (a CCRS UI change), relax the requirement "
                            + "for this tenant via POST /individual/v3/configs (W2), or set "
                            + "egov.user.v3.individual.default.gender explicitly as a recorded operator decision.");
        String gender = configured.trim().toUpperCase();
        if (!GENDERS.contains(gender))
            throw new CustomException("INDIVIDUAL_GENDER_INVALID",
                    "egov.user.v3.individual.default.gender is '" + configured + "'; v3 accepts only " + GENDERS + ".");
        return gender;
    }

    /**
     * Splits CCRS's single name into v3's two. The split is on the LAST space, and a name with no
     * space becomes a givenName alone - familyName is optional on create (measured), so there is
     * no need to duplicate the name into it and pretend.
     *
     * <p>Not defended against here, deliberately: v3 applies {@code ^[A-Za-z ]+$} to both parts by
     * default, so an initial, a dot, a hyphen or a digit is a 400. That failure is allowed to
     * reach the caller with v3's own wording. Stripping the offending characters would file the
     * complaint under a name that is not the citizen's, which is worse than telling them it could
     * not be done. The fix is a per-tenant nameRegex (W2), not code here.
     */
    private static void putName(ObjectNode body, String name) {
        if (name == null || name.trim().isEmpty())
            return;
        String trimmed = name.trim();
        int lastSpace = trimmed.lastIndexOf(' ');
        if (lastSpace < 0) {
            body.put("givenName", trimmed);
            body.remove("familyName");
            return;
        }
        body.put("givenName", trimmed.substring(0, lastSpace).trim());
        body.put("familyName", trimmed.substring(lastSpace + 1).trim());
    }

    /**
     * Fields CCRS holds that the individual registry has nowhere to put. Refused by name rather
     * than dropped: a correspondence address that vanishes on save is data loss at HTTP 200.
     */
    private static void refuseUnstorableFields(User user) {
        if (user.getCorrespondenceAddress() != null && !user.getCorrespondenceAddress().trim().isEmpty())
            throw new CustomException("INDIVIDUAL_FIELD_UNSUPPORTED",
                    "correspondenceAddress has no field in the DIGIT 3.0 individual registry (it is rejected as an "
                            + "unknown field on write, and there is no address sub-object). Saving the rest and "
                            + "dropping it would lose a citizen's address silently, at HTTP 200.");
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
