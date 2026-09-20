// record-user-gaps.js
//
// The user/individual gaps that are NOT per-request. src/translators/user.js
// gap-logs every field it invents on every call; these are the ones that were
// found by probing the live DIGIT 3.0 individual service and reading CCRS's
// Java, and that would otherwise never be written down because no CCRS request
// triggers them.
//
// Everything here was verified against the running d3-individual on
// egov-subhashini on 2026-09-20 with throwaway ZZPROBE records, not inferred
// from digit-specs/v3.0.0/individual.yaml. Where the spec and the deployed
// service disagree, both are stated and the service wins.
//
// Run: docker cp /opt/hybrid-shim/record-user-gaps.js hybrid-shim:/app/ && \
//      docker exec hybrid-shim node /app/record-user-gaps.js
const gaplog = require('/app/src/gaplog');

const DEP = 'egov-user';
const entries = [
  // ---------------------------------------------------------------- identity
  {
    field: 'RequestInfo.userInfo.uuid -> individual.id',
    action: 'unmapped',
    detail:
      'THE IDENTITY-MIGRATION REQUIREMENT. CCRS issues its own session: POST {kong}/user/oauth/token ' +
      'is served by 2.x egov-user and answers with an opaque UUID access token plus a UserRequest ' +
      'whose `uuid` is a row id in the egov-user Postgres schema. ' +
      'EnrichmentService.enrichCreateRequest:64 stamps that uuid onto every complaint as accountId, ' +
      'and UserService.enrichUser:110 then demands it back from the user service, throwing ' +
      'CustomException("INVALID_ACCOUNTID","No user exist for the given accountId") when it is ' +
      'absent. DIGIT 3.0 has never heard of that uuid and CANNOT BE TAUGHT IT: (1) POST ' +
      '/individual/v3/individuals SILENTLY IGNORES a caller-supplied `id` and mints its own - sent ' +
      'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee, got 629b9122-bc18-48d7-80fc-26731041e581 with HTTP 201, ' +
      'and the same for `individualId`; (2) the `userId` field, the only place a foreign identity ' +
      'could live, is stored but its search filter is IGNORED (see the next entry); (3) ' +
      '`additionalAttributes` can hold the 2.x uuid but is not a search filter, so it is write-only. ' +
      'A stateless translator therefore has NO mapping from a 2.x user uuid to a v3 individual id, ' +
      'and a stateful one would be a user-migration service, not a shim. Closing this needs: (a) ' +
      'every egov-user row migrated into the individual registry; (b) a durable 2.x-uuid -> ' +
      'individual-id map, or an individual create that honours a caller-supplied id; (c) sessions ' +
      'issued by one identity provider both halves trust. DIGIT 3.0 already names that provider - ' +
      'd3-keycloak is running and d3-individual carries KEYCLOAK_ENABLED=true, KEYCLOAK_BASE_URL and ' +
      'KEYCLOAK_ASSIGNABLE_ROLES=ASSIGNER,CITIZEN - and CCRS does not use it.',
  },
  {
    field: 'POST /user/_details?access_token=',
    action: 'unmapped',
    detail:
      "AUTHENTICATION DOES NOT MOVE WITH EGOV_USER_HOST, and that is why this switchover fails the " +
      'way it does. Grouping three days of the egov-user-proxy access log by source container IP ' +
      'shows POST /user/_details?access_token=... 2696 times from 172.19.0.35 = kong-gateway, and ' +
      "NEVER from 172.19.0.38 = pgr-services. The gateway validates every inbound CCRS session " +
      'against egov-user through its own upstream configuration, not through the variable this task ' +
      'flips. So after the flip CCRS logins still work and still mint 2.x uuids - the shim is not ' +
      'even asked. If something did point token validation at the shim it could not be served at ' +
      'all: a 2.x opaque access token has no representation anywhere in DIGIT 3.0, which expects a ' +
      'Keycloak-issued JWT. Migrating CCRS to v3 identity is therefore a login change, not a ' +
      'user-lookup change; re-pointing the lookup alone can only ever produce INVALID_ACCOUNTID.',
  },
  {
    field: 'individual.userId (search filter)',
    action: 'unmapped',
    detail:
      'v3 ACCEPTS AND SILENTLY IGNORES query filters it has not implemented, answering HTTP 200 with ' +
      'the whole tenant. Verified on the live service with four individuals in tenant pg: ' +
      '?userId=00000000-0000-0000-0000-000000000000 (a value NO individual carries) -> totalCount 4; ' +
      '?givenName=Zzprobe -> 4; ?wibble=xyz -> 4. individual.yaml documents UserIdFilter and ' +
      'GivenNameFilter as real parameters AND promises "Only the documented query parameters are ' +
      'accepted; unknown parameters return 400". Neither statement is true of the deployed build. ' +
      'The only filters observed to actually filter are `id` (repeatable), `individualId` and ' +
      '`mobileNumber`. This is the most dangerous behaviour found in either v3 service: a shim that ' +
      'trusted the spec would ask for one person, be handed the entire tenant with a success status, ' +
      'and return the WRONG CITIZEN to CCRS. The translator therefore sends only the three filters ' +
      'it has watched work and throws on every other 2.x criterion.',
  },
  // ---------------------------------------------------------------- deployment
  {
    field: 'vault transit/keys/IndividualEncrypt',
    action: 'unmapped',
    detail:
      'DEPLOYMENT PREREQUISITE, found the hard way. Every create against the v3 individual registry ' +
      'answered HTTP 500 ENCRYPTION_ERROR ("Failed to encrypt sensitive data", field mobileNumber) ' +
      'until this was fixed. d3-individual encrypts PII through Vault transit; on this box ' +
      '`vault secrets list` showed cubbyhole, identity, secret and sys only - THE TRANSIT ENGINE WAS ' +
      'NEVER MOUNTED - so every call became "vault returned status 404: no handler for route ' +
      'transit/encrypt/IndividualEncrypt". Fixed by register-individual-transit.sh (vault secrets ' +
      'enable transit; vault write -f transit/keys/IndividualEncrypt). Recorded as a gap because of ' +
      'what it means operationally: the individual service passed every health and readiness check, ' +
      'answered searches with HTTP 200, and was incapable of storing a single person. Nothing in the ' +
      'API surface reveals that until the first write. A DIGIT 3.0 rollout needs this key created as ' +
      'part of tenant onboarding, and a startup probe that proves the encrypt path works.',
  },
  {
    field: 'individual tenant Config (nameRegex/mobileRegex/uniquenessCriteria)',
    action: 'defaulted',
    detail:
      'GET /individual/v3/configs for tenant pg finds nothing (d3-individual logs "record not found" ' +
      'against individual_config_v3 on every create), so the registry falls back to platform ' +
      'defaults: givenName restricted to alphabets and spaces, no mobileRegex, no uniqueness ' +
      'criteria. Two consequences for a CCRS migration. (1) Names: CCRS citizens whose 2.x name ' +
      'holds an initial with a dot, a digit or a hyphen are REJECTED with 400 "givenName must ' +
      'contain only alphabets and spaces" (verified with "Zz9 Probe.") until nameRegex is relaxed ' +
      'through POST /individual/v3/configs. 2.x egov-user accepts any string, so this is a hard ' +
      'migration blocker for an unknown fraction of the existing citizen table. (2) Uniqueness: with ' +
      'no uniquenessCriteria configured, the registry happily stores TWO individuals with the same ' +
      'mobile number, while CCRS treats mobileNumber as the citizen identity ' +
      '(UserService.upsertUser:82 searches on it and takes .get(0)). Under v3 that silently picks ' +
      'one of several.',
  },
  // ---------------------------------------------------------------- contract
  {
    field: 'individual.gender',
    action: 'faked',
    detail:
      'SPEC AND IMPLEMENTATION DISAGREE, and the implementation wins. individual.yaml says "gender ' +
      'is optional, but when supplied must be one of the enum values"; the deployed service answers ' +
      '400 {"field":"gender","message":"gender is required"} to a create without one (verified). ' +
      "CCRS's web/models/User has no gender field at all and neither does the PGR complaint that " +
      'creates a citizen, so there is nothing whatsoever to map from. The shim sends "OTHER" ' +
      '(INDIVIDUAL_DEFAULT_GENDER) and records it on every create: this is an invented demographic ' +
      'fact about a real person being written into a registry of record. The real value can only ' +
      'come from asking the citizen, i.e. a CCRS registration-UI change.',
  },
  {
    field: 'PUT /individual/v3/individuals/{id} version',
    action: 'unmapped',
    detail:
      '2.x /user/users/_updatenovalidate CANNOT BE SERVED. v3 update is an optimistic-concurrency ' +
      'API: `version` is mandatory, and a PUT without one is refused 409 ROW_VERSION_MISMATCH ' +
      '{"expected":1,"provided":0} (verified live; the same PUT with version:1 answered 200). The 2.x ' +
      'CreateUserRequest payload carries no version and never has, so the shim would have to GET the ' +
      'individual and then PUT it - TWO v3 calls for one 2.x call, which the translator contract ' +
      '{toV3 -> one request, toV2 -> one response} cannot express. Faking version:1 was rejected: it ' +
      'would silently overwrite a concurrent edit, which is the exact thing the field exists to ' +
      'prevent. The translator throws instead (502 SHIM_ERROR). CCRS reaches this path from ' +
      'UserService.updateUser:155 (a citizen whose name changed on a new complaint) and ' +
      'updateUserContactDetails:261 (email/address write-back), so a real cutover loses both. ' +
      'Serving it needs a v3 update that can be told "latest", an If-Match header the caller may ' +
      'omit, or a stateful shim that caches versions.',
  },
  {
    field: 'individual search size',
    action: 'unmapped',
    detail:
      'v3 caps the individual search page at 100 (?size=200 -> 400) and the translator contract ' +
      'cannot page: one 2.x request maps to exactly one v3 request. UserService.searchBulkUser sends ' +
      'one uuid per complaint in a search result, so an employee inbox holding more than 100 ' +
      'complaints from distinct citizens cannot be enriched. The shim throws rather than answer the ' +
      'first 100, because a partial answer makes searchBulkUser attach a null citizen to every ' +
      'complaint past the boundary - silently. Compare the workflow service, which cannot serve even ' +
      'TWO ids (Task 5): the individual registry is strictly better here. Its repeated-parameter ' +
      'form ?id=a&id=b genuinely ORs the values (verified: both returned; ?id=<known>&id=<unknown> ' +
      '-> totalCount 1), while the comma form ?id=a,b answers 400. The translator uses the repeated ' +
      'form, so the multi-complaint employee inbox IS servable on the user dependency.',
  },
  {
    field: 'User.roles[] / User.type',
    action: 'dropped',
    detail:
      'SUPERSEDES an earlier entry with the same field name, which overstated the danger and is ' +
      'corrected here. CCRS needs seven fields on a User - id, uuid, userName, mobileNumber, type, ' +
      'roles[], tenantId - and the v3 Individual reports exactly two of them (uuid as `id`, ' +
      'mobileNumber). `roles`, `type` and `userName` are not merely absent: the create endpoint ' +
      'REJECTS all three as unknown fields (400 unknown field "userName"). So a citizen created ' +
      'through v3 exists as a person and as nothing else - no login, no authorisation, no user ' +
      'type. In DIGIT 3.0 those live in Keycloak realm roles against a Keycloak user ' +
      '(d3-individual: KEYCLOAK_ENABLED=true, KEYCLOAK_ASSIGNABLE_ROLES=ASSIGNER,CITIZEN), a ' +
      'second service with its own admin API and its own credentials. ' +
      'WHAT THE SHIM DOES ABOUT IT: on the SEARCH path it reports roles: [] and type: null - it ' +
      'reports what v3 reports, which is nothing. An earlier revision stamped ' +
      '[{code:"CITIZEN"}] / "CITIZEN" on every individual a search returned, which would have ' +
      'labelled EMPLOYEES as citizens; that is fixed. On the CREATE path it echoes back the roles ' +
      'and type the caller itself just sent, which is the caller hearing its own assertion, not an ' +
      'invention (and UserService.upsertUser:97 reads only .getUuid() from that reply anyway). ' +
      'BLAST RADIUS, TRACED EXHAUSTIVELY RATHER THAN ASSUMED - AND THIS IS THE CORRECTION: this is ' +
      'NOT a privilege path. pgr-services has exactly ONE consumer of roles on a user the USER ' +
      'SERVICE returned: UserService.getFirstRoleNameByUuid:241, whose single caller is ' +
      'ComplaintDomainEventService.getAssigneeName:226 - a DISPLAY STRING on an outbound domain ' +
      'event, null-tolerant on both hops (empty roles -> null -> the literal "NA"). Nothing reads ' +
      'the `type` of a user the user service returned at all. Every genuine authorisation site ' +
      'reads org.egov.common.contract.request.User from RequestInfo.userInfo, a DIFFERENT Java ' +
      'class from the org.egov.pgr.web.models.User the shim answers into: ' +
      'PGRService.hasAnyRole:438-442 behind isAuthorizedForConfidential:531 (confidential-field ' +
      'masking), Principals.isPureCitizen:55, RoleCodes.normalize:29 (MDMSUtils:809, ' +
      'AnalyticsCapabilityService:43, AccessPolicyRegistry:493, PolicyDrivenScopeResolver:171 from ' +
      'requestInfo.getUserInfo() at :86), EmployeeContextService:154, PolicyInputBuilder:27. ' +
      'RequestInfo.userInfo is filled by the GATEWAY from the 2.x token, and the gateway validates ' +
      'sessions through its own upstream, NOT through EGOV_USER_HOST - so a shim response is ' +
      'structurally unable to reach a privilege decision. Worst observed outcome of the old ' +
      'invented roles: a domain event naming the wrong assignee role. ' +
      'THE MIGRATION REQUIREMENT IS UNCHANGED AND IS THE POINT OF THIS ENTRY: v3 carries neither ' +
      'roles nor user type, so a real cutover must source both from Keycloak realm roles - which ' +
      'needs the Keycloak admin API, its own credentials, and a 2.x-role -> realm-role mapping ' +
      'nobody has written.',
  },
  {
    field: 'User.id',
    action: 'dropped',
    detail:
      "CCRS's User.id is a Long - the egov-user table's primary key - and 2.x code joins on it " +
      '(MigrationService:266 parses it to look up a uuid). The v3 individual registry is uuid-keyed ' +
      'and has no numeric id, so the shim emits null rather than inventing a number that would ' +
      'collide with a real egov-user row. v3 offers `individualId` (e.g. IND-f61cafe2) as a ' +
      'human-facing identifier, but it is a string with different semantics and is NOT a substitute.',
  },
  {
    field: 'User.createdDate',
    action: 'faked',
    detail:
      'A v3 individual reply must be reshaped to include createdDate as "dd-MM-yyyy HH:mm:ss" or ' +
      'pgr-services CRASHES. UserUtils.parseResponse:79 runs ' +
      'dateTolong((String) map.get("createdDate"), "dd-MM-yyyy HH:mm:ss") over every user in the ' +
      'response with no null check, so a missing value throws a NullPointerException inside ' +
      'SimpleDateFormat.parse() - a 500 from pgr-services, not a clean error. v3 reports epoch millis ' +
      'in auditDetail.createdTime with no timezone; 2.x egov-user formats in the server\'s local ' +
      'zone. The shim formats in UTC, so a round trip shifts the value by the host offset, and when ' +
      'v3 reports no audit block at all it substitutes NOW - a lie about when the person was ' +
      'registered, taken because the alternative is a crash.',
  },
];

for (const e of entries) gaplog.record(Object.assign({ dep: DEP }, e));
process.stdout.write(`recorded ${entries.length} user/individual gap entries\n`);
