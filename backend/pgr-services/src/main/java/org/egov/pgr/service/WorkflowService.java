package org.egov.pgr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.User;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.egov.pgr.util.V3Headers;
import org.egov.pgr.web.models.*;
import org.egov.pgr.web.models.workflow.*;
import org.egov.pgr.workflow.WorkflowV3Mapper;
import org.egov.tracer.model.CustomException;
import org.egov.tracer.model.ServiceCallException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.util.CollectionUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.egov.pgr.util.PGRConstants.*;

/**
 * pgr-services' whole relationship with the workflow engine.
 *
 * <p>There are FIVE distinct call shapes behind three 2.x paths, and they are enumerated here
 * rather than built inline at each call site, because under DIGIT 3.0 they do not all map the same
 * way and a sub-path that gets the wrong shape comes back 200 and empty, not as an error:
 *
 * <ol>
 *   <li>{@code POST /process/_transition} — one complaint, one action</li>
 *   <li>{@code POST /businessservice/_search?tenantId=&businessServices=PGR} — the process definition</li>
 *   <li>{@code POST /process/_search?tenantId=&businessIds=<CSV>} — the complaint-search join</li>
 *   <li>{@code ...&businessIds=<one>&history=true} — the full ordered transition history</li>
 *   <li>{@code ...&businessService=PGR&assignee=<uuid>} — the employee inbox by assignee</li>
 * </ol>
 *
 * <p>Shapes 4 and 5 are built by {@code NotificationService} and {@code EscalationService}, which
 * used to reach in and string-append {@code "&history=true"} onto the URL shape 3 produced. They
 * now ask for the shape they want by name, so that a v3 flip cannot leave a caller appending a
 * query parameter to a URL that no longer has that query.
 */
@org.springframework.stereotype.Service
@lombok.extern.slf4j.Slf4j
public class WorkflowService {

    /** v3 answers 400 "size must be an integer between 1 and 100" outside this range. */
    private static final int V3_MAX_PAGE_SIZE = 100;

    private PGRConfiguration pgrConfiguration;

    private ServiceRequestRepository repository;

    private ObjectMapper mapper;

    /**
     * Not a constructor argument: a fourth one would be a compile break for every test that builds
     * this class by hand, for no gain. The mapper is a pure function of the ObjectMapper already
     * being injected.
     */
    private final WorkflowV3Mapper v3Mapper;

    @Autowired
    public WorkflowService(PGRConfiguration pgrConfiguration, ServiceRequestRepository repository, ObjectMapper mapper) {
        this.pgrConfiguration = pgrConfiguration;
        this.repository = repository;
        this.mapper = mapper;
        this.v3Mapper = new WorkflowV3Mapper(mapper);
    }

    /*
     *
     * Should return the applicable BusinessService for the given request
     *
     * */
    public BusinessService getBusinessService(ServiceRequest serviceRequest) {
        return getBusinessService(serviceRequest.getRequestInfo(), serviceRequest.getService().getTenantId());
    }

    /**
     * The PGR process definition for a tenant, on whichever stack the workflow flag selects.
     *
     * <p>Shared with {@code MigrationUtils}, which had its own copy of the URL builder and its own
     * copy of the parse. One definition search, one place.
     */
    public BusinessService getBusinessService(RequestInfo requestInfo, String tenantId) {
        List<BusinessService> businessServices = pgrConfiguration.isWorkflowV3()
                ? searchBusinessServicesV3(requestInfo, tenantId)
                : searchBusinessServices2x(requestInfo, tenantId);

        if (CollectionUtils.isEmpty(businessServices))
            throw new CustomException("BUSINESSSERVICE_NOT_FOUND", "The businessService " + PGR_BUSINESSSERVICE + " is not found");

        return businessServices.get(0);
    }

    private List<BusinessService> searchBusinessServices2x(RequestInfo requestInfo, String tenantId) {
        StringBuilder url = getBusinessServiceSearchURL(tenantId);
        RequestInfoWrapper requestInfoWrapper = RequestInfoWrapper.builder().requestInfo(requestInfo).build();
        Object result = repository.fetchResult(url, requestInfoWrapper);
        return parseBusinessServices(result).getBusinessServices();
    }

    /**
     * {@code GET /workflow/v3/process/definition?processCode=PGR}, tenant in {@code X-Tenant-ID}.
     *
     * <p>2.x spelled the filter {@code businessServices}, put the tenant on the query string and
     * sent a RequestInfo body. v3 does none of the three, and the envelope key is
     * {@code definitions}, not {@code BusinessServices} - a rename CCRS's case-insensitive
     * ObjectMapper cannot absorb, which is why this goes through {@link WorkflowV3Mapper} rather
     * than {@code convertValue}.
     *
     * <p>No {@code size} is sent: probed live on 2026-09-21, this endpoint IGNORES it ({@code
     * size=1} returned all three registered definitions and echoed {@code "size":3}), so asking
     * would be theatre. The truncation assertion below is kept anyway, against the day it starts
     * paging - v3 truncates with no error and the 2.x envelope has no field to carry "there was
     * more".
     *
     * <p>X-User-ID is sent when the caller has one but is not required here; the definition search
     * answers 200 without it, unlike the transition search.
     */
    private List<BusinessService> searchBusinessServicesV3(RequestInfo requestInfo, String tenantId) {
        String which = "GET " + pgrConfiguration.getWorkflowV3ProcessDefinitionSearchPath();
        String uri = UriComponentsBuilder
                .fromHttpUrl(joinUrl(pgrConfiguration.getWorkflowV3Host(),
                        pgrConfiguration.getWorkflowV3ProcessDefinitionSearchPath()))
                .queryParam("processCode", PGR_BUSINESSSERVICE)
                .encode()
                .toUriString();

        Map<?, ?> body = repository.exchange(HttpMethod.GET, uri, null,
                V3Headers.forTenant(workflowTenant(requestInfo, tenantId))
                        .withUserFrom(requestInfo)
                        .build(),
                Map.class);

        List<Map<String, Object>> rows = v3Mapper.rowsOf(body, WorkflowV3Mapper.DEFINITIONS_KEY, which);
        v3Mapper.assertNotTruncated(v3Mapper.totalCountOf(body), rows.size(), which);

        List<BusinessService> businessServices = new ArrayList<>();
        for (Map<String, Object> row : rows)
            businessServices.add(v3Mapper.businessService(row, tenantId));
        return businessServices;
    }


    /*
     * Call the workflow service with the given action and update the status
     * return the updated status of the application
     *
     * */
    public String updateWorkflowStatus(ServiceRequest serviceRequest) {
        // Before ANY call. Resolving the business service first and only then discovering that the
        // action cannot be performed would spend a round trip to reach the same refusal.
        if (pgrConfiguration.isWorkflowV3() && serviceRequest.getWorkflow() != null)
            refuseIfUnsupportedOnV3(serviceRequest.getWorkflow().getAction(),
                    serviceRequest.getService() == null ? null : serviceRequest.getService().getServiceRequestId());

        ProcessInstance processInstance = getProcessInstanceForPGR(serviceRequest);
        ProcessInstanceRequest workflowRequest = new ProcessInstanceRequest(serviceRequest.getRequestInfo(), Collections.singletonList(processInstance));
        ProcessInstanceResponse response = callWorkFlow(workflowRequest);
        serviceRequest.getService().setApplicationStatus(response.getProcessInstances().get(0).getState().getApplicationStatus());
        serviceRequest.getService().setProcessInstance(response.getProcessInstances().get(0));
        return response.getProcessInstances().get(0).getState().getApplicationStatus();
    }


    public void validateAssignee(ServiceRequest serviceRequest) {
        /*
         * Call HRMS service and validate of the assignee belongs to same department
         * as the employee assigning it
         *
         * */

    }

    // ------------------------------------------------------------------ 2.x URL shapes

    /**
     * Shape 2 — the process definition search.
     *
     * @param tenantId The tenantId for which url is generated
     * @return The search url
     */
    public StringBuilder getBusinessServiceSearchURL(String tenantId) {
        StringBuilder url = new StringBuilder(pgrConfiguration.getWfHost());
        url.append(pgrConfiguration.getWfBusinessServiceSearchPath());
        url.append("?tenantId=");
        url.append(tenantId);
        url.append("&businessServices=");
        url.append(PGR_BUSINESSSERVICE);
        return url;
    }

    /**
     * Shapes 3, 4 and 5 — the one 2.x process-search path, whose three query shapes mean three
     * different things. Kept as one builder so the three cannot drift apart.
     *
     * @param businessIds     comma-separated complaint ids, or null for the assignee shape
     * @param businessService the business service, sent only on the assignee shape by 2.x
     * @param assignee        an employee uuid, or null
     * @param history         true for the full ordered transition history rather than the latest row
     */
    private StringBuilder processInstanceSearchURL(String tenantId, String businessIds,
                                                   String businessService, String assignee, boolean history) {
        StringBuilder url = new StringBuilder(pgrConfiguration.getWfHost());
        url.append(pgrConfiguration.getWfProcessInstanceSearchPath());
        url.append("?tenantId=").append(tenantId);
        if (!StringUtils.isEmpty(businessIds))
            url.append("&businessIds=").append(businessIds);
        if (!StringUtils.isEmpty(businessService))
            url.append("&businessService=").append(businessService);
        if (!StringUtils.isEmpty(assignee))
            url.append("&assignee=").append(assignee);
        if (history)
            url.append("&history=true");
        return url;
    }

    /** Shape 3 — the latest transition for one or more complaints. */
    public StringBuilder getprocessInstanceSearchURL(String tenantId, String serviceRequestId) {
        return processInstanceSearchURL(tenantId, serviceRequestId, null, null, false);
    }

    /**
     * Shape 4 — the full ordered transition history of one complaint.
     *
     * <p>Callers used to build shape 3 and append {@code "&history=true"} themselves. They ask for
     * it by name now: a URL built somewhere else and amended here is exactly the coupling that
     * breaks when the underlying call stops being a query string.
     */
    public StringBuilder getProcessInstanceHistorySearchURL(String tenantId, String serviceRequestId) {
        return processInstanceSearchURL(tenantId, serviceRequestId, null, null, true);
    }

    /** Shape 5 — every complaint currently assigned to one employee. */
    public StringBuilder getProcessInstanceSearchURLByAssignee(String tenantId, String assigneeUuid) {
        return processInstanceSearchURL(tenantId, null, PGR_BUSINESSSERVICE, assigneeUuid, false);
    }

    // ------------------------------------------------------------------ response parsing

    /** The 2.x ProcessInstance envelope. Gathered here so both stacks land in one place. */
    public ProcessInstanceResponse parseProcessInstances(Object result) {
        try {
            return mapper.convertValue(result, ProcessInstanceResponse.class);
        } catch (IllegalArgumentException e) {
            throw new CustomException("PARSING ERROR", "Failed to parse response of workflow processInstance search");
        }
    }

    /** The 2.x BusinessService envelope. */
    public BusinessServiceResponse parseBusinessServices(Object result) {
        try {
            return mapper.convertValue(result, BusinessServiceResponse.class);
        } catch (IllegalArgumentException e) {
            throw new CustomException("PARSING ERROR", "Failed to parse response of workflow business service search");
        }
    }


    public void enrichmentForSendBackToCititzen() {
        /*
         * If send bac to citizen action is taken assignes should be set to accountId
         *
         * */
    }


    public List<ServiceWrapper> enrichWorkflow(RequestInfo requestInfo, List<ServiceWrapper> serviceWrappers) {

        // FIX ME FOR BULK SEARCH
        Map<String, List<ServiceWrapper>> tenantIdToServiceWrapperMap = getTenantIdToServiceWrapperMap(serviceWrappers);

        List<ServiceWrapper> enrichedServiceWrappers = new ArrayList<>();

        for(String tenantId : tenantIdToServiceWrapperMap.keySet()) {

            List<String> serviceRequestIds = new ArrayList<>();

            List<ServiceWrapper> tenantSpecificWrappers = tenantIdToServiceWrapperMap.get(tenantId);

            tenantSpecificWrappers.forEach(pgrEntity -> {
                serviceRequestIds.add(pgrEntity.getService().getServiceRequestId());
            });

            List<ProcessInstance> processInstances = searchProcessInstances(requestInfo, tenantId, serviceRequestIds);

            if (CollectionUtils.isEmpty(processInstances) || processInstances.size() != serviceRequestIds.size())
                throw new CustomException("WORKFLOW_NOT_FOUND", "The workflow object is not found");

            Map<String, Workflow> businessIdToWorkflow = getWorkflow(processInstances);

            tenantSpecificWrappers.forEach(pgrEntity -> {
                pgrEntity.setWorkflow(businessIdToWorkflow.get(pgrEntity.getService().getServiceRequestId()));
            });

            enrichedServiceWrappers.addAll(tenantSpecificWrappers);
        }

        return enrichedServiceWrappers;

    }

    /**
     * The latest transition for each of the given complaints.
     *
     * <p>This is the inbox join, and it is the one call shape v3 genuinely cannot serve. 2.x
     * answers every id in one request with a comma-separated {@code businessIds}. v3's
     * {@code GET /workflow/v3/transition} serves exactly ONE {@code entityId}: measured live on
     * 2026-09-21, the comma form answers {@code totalCount 0} and the repeated form
     * {@code ?entityId=a&entityId=b} honours only the FIRST value - both with HTTP 200. A
     * two-complaint employee inbox therefore comes back with one row, or none, and looks like a
     * successful search.
     *
     * <p>On v3 this therefore fans out: N complaints become N single-entity calls, gathered. The
     * comma-separated form is never sent, on either stack's behalf.
     */
    public List<ProcessInstance> searchProcessInstances(RequestInfo requestInfo, String tenantId,
                                                        List<String> serviceRequestIds) {
        if (CollectionUtils.isEmpty(serviceRequestIds))
            return Collections.emptyList();

        if (!pgrConfiguration.isWorkflowV3()) {
            RequestInfoWrapper requestInfoWrapper = RequestInfoWrapper.builder().requestInfo(requestInfo).build();
            StringBuilder searchUrl = getprocessInstanceSearchURL(tenantId, StringUtils.join(serviceRequestIds, ','));
            Object result = repository.fetchResult(searchUrl, requestInfoWrapper);
            return parseProcessInstances(result).getProcessInstances();
        }

        if (serviceRequestIds.size() == 1)
            return searchProcessInstancesV3(requestInfo, tenantId, serviceRequestIds.get(0), null, false);

        return fanOutProcessInstanceSearch(requestInfo, tenantId, serviceRequestIds);
    }

    /**
     * N complaints, N single-entity v3 calls, gathered.
     *
     * <p>This is the one place being a BFF beats being a shim. A translator is one request in and
     * one request out, so it could only forward the comma-separated {@code businessIds} 2.x sends
     * and let v3 answer {@code totalCount 0} - which CCRS then reported as WORKFLOW_NOT_FOUND, an
     * employee inbox holding two or more complaints simply failing. pgr-services can make N calls.
     *
     * <p>What this costs is not hidden: a 25-complaint inbox page is 25 requests to the workflow
     * service where 2.x made 1. The concurrency and the overall timeout are configurable
     * ({@code egov.workflow.v3.fanout.threads}, {@code .timeout.seconds}) because nothing in this
     * migration has measured v3 under concurrency; the honest position is that this is a knob, not
     * a tuned constant. The real fix is a v3 search that accepts more than one entityId.
     *
     * <p>Every failure mode here is loud. One call failing fails the whole join rather than
     * yielding a short list - a partially-enriched inbox would be a page of complaints at HTTP 200
     * with some of them silently missing their workflow, which is precisely the failure shape this
     * migration exists to stop. An id that simply has no workflow contributes no row, and
     * {@link #enrichWorkflow}'s count check then raises WORKFLOW_NOT_FOUND, exactly as it does on
     * 2.x.
     *
     * <p>Results are returned in the order the ids were asked for, so the join is deterministic.
     */
    private List<ProcessInstance> fanOutProcessInstanceSearch(RequestInfo requestInfo, String tenantId,
                                                              List<String> serviceRequestIds) {
        int threads = Math.max(1, Math.min(fanoutThreads(), serviceRequestIds.size()));
        ExecutorService executor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "wf-v3-fanout");
            thread.setDaemon(true);
            return thread;
        });

        try {
            List<Future<List<ProcessInstance>>> futures = new ArrayList<>(serviceRequestIds.size());
            for (String serviceRequestId : serviceRequestIds)
                futures.add(executor.submit(() ->
                        searchProcessInstancesV3(requestInfo, tenantId, serviceRequestId, null, false)));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(fanoutTimeoutSeconds());
            List<ProcessInstance> gathered = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    long remaining = deadline - System.nanoTime();
                    gathered.addAll(futures.get(i).get(Math.max(remaining, 0L), TimeUnit.NANOSECONDS));
                } catch (TimeoutException e) {
                    throw new CustomException("WORKFLOW_FANOUT_TIMEOUT",
                            "Timed out joining " + serviceRequestIds.size() + " complaints to their workflows. "
                                    + "DIGIT 3.0 serves one entityId per call, so this search is "
                                    + serviceRequestIds.size() + " requests where 2.x made one; it gave up after "
                                    + fanoutTimeoutSeconds() + "s with " + gathered.size() + " answered. Returning a "
                                    + "partial inbox would be worse than failing.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CustomException("WORKFLOW_FANOUT_INTERRUPTED",
                            "Interrupted joining complaint " + serviceRequestIds.get(i) + " to its workflow");
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    // Rethrown untouched where possible: ServiceCallException keeps its text in
                    // getError() and CustomException in getCode(), and re-wrapping either discards
                    // the only diagnosis there is.
                    if (cause instanceof RuntimeException)
                        throw (RuntimeException) cause;
                    throw new CustomException("WORKFLOW_NOT_FOUND",
                            "Failed to join complaint " + serviceRequestIds.get(i) + " to its workflow: " + cause);
                }
            }
            return gathered;
        } finally {
            executor.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ actions v3 cannot perform

    /**
     * Refuses an action DIGIT 3.0 stores and will not perform.
     *
     * <p><b>The decision, stated once.</b> CCRS models COMMENT and ESCALATE as self-loops
     * ({@code nextState == currentState}). v3 accepts both at registration and answers every
     * attempt with 409 "invalid state transition: no transition". Three options were on the table:
     *
     * <ul>
     *   <li><b>Degrade</b> - keep accepting the action and quietly do nothing, or keep only the
     *       comments that ride a real state change. Rejected. A citizen's comment accepted at HTTP
     *       200 and silently discarded is the exact failure shape this migration exists to stop,
     *       and it would be invisible in every log and every test.</li>
     *   <li><b>Keep it on 2.x</b> - route these two actions to the old workflow service while the
     *       rest goes to v3. Rejected. There is one process instance per complaint and it lives in
     *       one engine; a comment written into the 2.x store for a complaint whose state machine is
     *       in v3 is a split brain, not a fallback.</li>
     *   <li><b>Fail loudly</b> - chosen. When the workflow dependency is on v3, CCRS refuses the
     *       action at its own boundary, before the call, with an error that names the action, the
     *       complaint, the reason and the way out. The citizen or employee is told their comment
     *       was not recorded, which is true, instead of being told it was, which is not.</li>
     * </ul>
     *
     * <p>This is a deliberate, visible product regression on the v3 flag, and it is the honest
     * shape of the underlying fact: standalone citizen commenting is a CCRS product feature that
     * should never have been modelled as a workflow self-loop. Owning it - a CCRS comment table
     * and endpoint, independent of the workflow engine - is the real fix and is a separate piece of
     * work. Until then the flag makes the loss explicit rather than silent, and emptying
     * {@code egov.workflow.v3.unsupported.actions} adopts a v3 build that starts supporting
     * self-loops with no code change.
     *
     * <p>ESCALATE is dead twice over: even with self-loops working, CCRS escalation re-targets the
     * complaint to the current assignee's {@code reportingTo}, and v3's employee registry has no
     * supervisor column at all. See EscalationScheduler, which refuses to sweep on v3.
     */
    private void refuseIfUnsupportedOnV3(String action, String businessId) {
        if (action == null || !unsupportedV3Actions().contains(action.trim().toUpperCase(Locale.ROOT)))
            return;
        throw unsupportedActionOnV3(action, businessId,
                "v3 stores this action on the process definition and refuses it at runtime, because CCRS models "
                        + "it as a self-loop (nextState == currentState) and the DIGIT 3.0 engine has no such "
                        + "transition");
    }

    private CustomException unsupportedActionOnV3(String action, String businessId, String reason) {
        return new CustomException("WORKFLOW_ACTION_UNSUPPORTED_ON_V3",
                "The action " + action + " cannot be performed on complaint " + businessId
                        + " while the workflow dependency is on DIGIT 3.0: " + reason
                        + ". Nothing was recorded. This is refused here rather than attempted and swallowed, because "
                        + "an action accepted at HTTP 200 and silently discarded is worse than one that fails. To "
                        + "restore it: set egov.workflow.api.version=2.x, or have CCRS own the feature outside the "
                        + "workflow engine, or clear egov.workflow.v3.unsupported.actions once a v3 build supports "
                        + "self-loop transitions.");
    }

    /** v3's refusal of a self-loop, as the live build words it. */
    private static boolean isSelfLoopRefusal(ServiceCallException e) {
        String error = e.getError();
        return error != null && error.contains("409") && error.contains("invalid state transition");
    }

    private Set<String> unsupportedV3Actions() {
        String configured = pgrConfiguration.getWorkflowV3UnsupportedActions();
        if (configured == null || configured.trim().isEmpty())
            return Collections.emptySet();
        Set<String> actions = new HashSet<>();
        for (String action : configured.split(","))
            if (!action.trim().isEmpty())
                actions.add(action.trim().toUpperCase(Locale.ROOT));
        return actions;
    }

    private int fanoutThreads() {
        Integer configured = pgrConfiguration.getWorkflowV3FanoutThreads();
        return configured == null || configured < 1 ? 1 : configured;
    }

    private int fanoutTimeoutSeconds() {
        Integer configured = pgrConfiguration.getWorkflowV3FanoutTimeoutSeconds();
        return configured == null || configured < 1 ? 30 : configured;
    }

    /**
     * The full ordered transition history of one complaint, oldest first.
     *
     * <p>{@code NotificationService.getEmployeeName} scans it for the last matching action and
     * {@code EscalationService} counts ESCALATEs in it, so an incomplete history resolves the wrong
     * assignee or the wrong escalation level rather than failing.
     */
    public List<ProcessInstance> searchProcessInstanceHistory(RequestInfo requestInfo, String tenantId,
                                                              String serviceRequestId) {
        if (!pgrConfiguration.isWorkflowV3()) {
            RequestInfoWrapper requestInfoWrapper = RequestInfoWrapper.builder().requestInfo(requestInfo).build();
            StringBuilder url = getProcessInstanceHistorySearchURL(tenantId, serviceRequestId);
            Object result = repository.fetchResult(url, requestInfoWrapper);
            return parseProcessInstances(result).getProcessInstances();
        }
        return searchProcessInstancesV3(requestInfo, tenantId, serviceRequestId, null, true);
    }

    /**
     * {@code GET /workflow/v3/transition}, the one v3 endpoint behind all three 2.x process-search
     * shapes.
     *
     * <pre>
     *   ?processCode=PGR&entityId=&lt;one&gt;[&amp;assignee=][&amp;history=true]&amp;size=100
     *   X-Tenant-ID: &lt;tenant&gt;      X-User-ID: &lt;acting uuid&gt;
     * </pre>
     *
     * <p>Three things here are not in the 2.x call and each was verified live:
     * <ul>
     *   <li><b>{@code processCode} is mandatory</b> - 400 "processCode query parameter is required
     *       when filtering by entityId". 2.x sends none, because business ids are globally unique
     *       there. It is supplied explicitly from PGR_BUSINESSSERVICE rather than defaulted from
     *       config, so a second module behind this call cannot be searched under the wrong process
     *       and answered empty with HTTP 200.</li>
     *   <li><b>{@code X-User-Id} is mandatory</b> on this endpoint - 400 "X-User-Id header is
     *       required" - although the definition search does not need it. It is checked here rather
     *       than left to produce a 400 three frames away.</li>
     *   <li><b>The tenant is a header, not a query parameter.</b></li>
     * </ul>
     */
    private List<ProcessInstance> searchProcessInstancesV3(RequestInfo requestInfo, String tenantId,
                                                           String entityId, String assigneeUuid, boolean history) {
        String which = "GET " + pgrConfiguration.getWorkflowV3TransitionPath();
        String userId = V3Headers.userIdOf(requestInfo);
        if (userId == null)
            throw new CustomException("WORKFLOW_ACTOR_MISSING",
                    "No acting user uuid to put in X-User-ID. DIGIT 3.0 " + which + " answers 400 "
                            + "\"X-User-Id header is required\" without it, so the call cannot be made.");

        Map<String, String> headers = V3Headers.forTenant(workflowTenant(requestInfo, tenantId))
                .withUserFrom(requestInfo)
                .build();

        int size = pageSize();
        int maxPages = maxPages();
        List<Map<String, Object>> rows = new ArrayList<>();
        Long totalCount = null;

        for (int page = 0; page < maxPages; page++) {
            UriComponentsBuilder uri = UriComponentsBuilder
                    .fromHttpUrl(joinUrl(pgrConfiguration.getWorkflowV3Host(), pgrConfiguration.getWorkflowV3TransitionPath()))
                    .queryParam("processCode", PGR_BUSINESSSERVICE);
            if (!StringUtils.isEmpty(entityId))
                uri.queryParam("entityId", entityId);
            if (!StringUtils.isEmpty(assigneeUuid))
                uri.queryParam("assignee", assigneeUuid);
            if (history)
                uri.queryParam("history", "true");
            uri.queryParam("size", size);
            uri.queryParam("page", page);

            Map<?, ?> body = repository.exchange(HttpMethod.GET, uri.encode().toUriString(), null, headers, Map.class);

            List<Map<String, Object>> pageRows = v3Mapper.rowsOf(body, WorkflowV3Mapper.PROCESS_INSTANCES_KEY, which);
            totalCount = v3Mapper.totalCountOf(body);
            rows.addAll(pageRows);

            // Stop on the first short or empty page. v3 answers a page past the end with an empty
            // list and the same totalCount, so a page that came back short cannot be followed by a
            // full one; continuing would be an extra round trip per call for nothing.
            if (pageRows.size() < size)
                break;
            if (totalCount != null && rows.size() >= totalCount)
                break;
        }

        // Either v3 said there were more than CCRS collected, or the page bound was reached with
        // work left. Both are reported rather than returned: the 2.x envelope has no field for
        // "there was more", so a short list here is indistinguishable from a complete one, and the
        // two callers that hurt most are an assignee inbox losing every complaint past the cap and
        // a >100-transition history making getEmployeeName resolve the wrong assignee.
        v3Mapper.assertNotTruncated(totalCount, rows.size(), which);

        List<ProcessInstance> processInstances = new ArrayList<>();
        for (Map<String, Object> row : rows)
            processInstances.add(v3Mapper.processInstance(row, tenantId, PGR_BUSINESSSERVICE,
                    PGR_MODULENAME, V3Headers.userIdOf(requestInfo), which));
        return processInstances;
    }

    /**
     * v3's page size, clamped into the range it actually accepts.
     *
     * <p>Measured 2026-09-21: {@code size=0} and {@code size=101} are both answered 400 "size must
     * be an integer between 1 and 100", and omitting it silently gives 20 - which is the more
     * dangerous of the two, because 20 rows of a longer history look exactly like a complete
     * answer. It is always sent.
     */
    private int pageSize() {
        Integer configured = pgrConfiguration.getV3PageMaxSize();
        int size = configured == null ? V3_MAX_PAGE_SIZE : configured;
        return Math.min(Math.max(size, 1), V3_MAX_PAGE_SIZE);
    }

    private int maxPages() {
        Integer configured = pgrConfiguration.getWorkflowV3MaxPages();
        return configured == null || configured < 1 ? 1 : configured;
    }

    /** workflow tenancy: the tenant the caller named, then the acting user's. */
    private static String workflowTenant(RequestInfo requestInfo, String tenantId) {
        return V3Headers.workflowTenant(requestInfo, tenantId, null);
    }

    private static String joinUrl(String host, String endpoint) {
        if (host.endsWith("/") && endpoint.startsWith("/"))
            return host + endpoint.substring(1);
        if (!host.endsWith("/") && !endpoint.startsWith("/"))
            return host + "/" + endpoint;
        return host + endpoint;
    }

    private Map<String, List<ServiceWrapper>> getTenantIdToServiceWrapperMap(List<ServiceWrapper> serviceWrappers) {
        Map<String, List<ServiceWrapper>> resultMap = new HashMap<>();
        for(ServiceWrapper serviceWrapper : serviceWrappers){
            if(resultMap.containsKey(serviceWrapper.getService().getTenantId())){
                resultMap.get(serviceWrapper.getService().getTenantId()).add(serviceWrapper);
            }else{
                List<ServiceWrapper> serviceWrapperList = new ArrayList<>();
                serviceWrapperList.add(serviceWrapper);
                resultMap.put(serviceWrapper.getService().getTenantId(), serviceWrapperList);
            }
        }
        return resultMap;
    }

    /**
     * Enriches ProcessInstance Object for workflow
     *
     * @param request
     */
    private ProcessInstance getProcessInstanceForPGR(ServiceRequest request) {

        Service service = request.getService();
        Workflow workflow = request.getWorkflow();

        ProcessInstance processInstance = new ProcessInstance();
        processInstance.setBusinessId(service.getServiceRequestId());
        processInstance.setAction(request.getWorkflow().getAction());
        processInstance.setModuleName(PGR_MODULENAME);
        processInstance.setTenantId(service.getTenantId());
        processInstance.setBusinessService(getBusinessService(request).getBusinessService());
        processInstance.setDocuments(request.getWorkflow().getVerificationDocuments());
        processInstance.setComment(workflow.getComments());

        if(!CollectionUtils.isEmpty(workflow.getAssignes())){
            List<User> users = new ArrayList<>();

            workflow.getAssignes().forEach(uuid -> {
                User user = new User();
                user.setUuid(uuid);
                users.add(user);
            });

            processInstance.setAssignes(users);
        }

        return processInstance;
    }

    /**
     *
     * @param processInstances
     */
    public Map<String, Workflow> getWorkflow(List<ProcessInstance> processInstances) {

        Map<String, Workflow> businessIdToWorkflow = new HashMap<>();

        processInstances.forEach(processInstance -> {
            List<String> userIds = null;

            if(!CollectionUtils.isEmpty(processInstance.getAssignes())){
                userIds = processInstance.getAssignes().stream().map(User::getUuid).collect(Collectors.toList());
            }

            Workflow workflow = Workflow.builder()
                    .action(processInstance.getAction())
                    .assignes(userIds)
                    .comments(processInstance.getComment())
                    .verificationDocuments(processInstance.getDocuments())
                    .build();

            businessIdToWorkflow.put(processInstance.getBusinessId(), workflow);
        });

        return businessIdToWorkflow;
    }

    /**
     * Method to integrate with workflow
     * <p>
     * take the ProcessInstanceRequest as paramerter to call wf-service
     * <p>
     * and return wf-response to sets the resultant status
     */
    private ProcessInstanceResponse callWorkFlow(ProcessInstanceRequest workflowReq) {

        if (pgrConfiguration.isWorkflowV3())
            return transitionV3(workflowReq);

        StringBuilder url = new StringBuilder(pgrConfiguration.getWfHost().concat(pgrConfiguration.getWfTransitionPath()));
        Object optional = repository.fetchResult(url, workflowReq);
        return mapper.convertValue(optional, ProcessInstanceResponse.class);
    }

    /**
     * {@code POST /workflow/v3/transition} — one complaint, one action.
     *
     * <pre>
     *   X-Tenant-ID: &lt;tenant&gt;   X-User-ID: &lt;acting uuid&gt;   Authorization: Bearer &lt;jwt&gt;
     *   {"processCode":"PGR","entityId":"&lt;complaint&gt;","action":"ASSIGN",
     *    "comment":"...","documents":[...],"assignees":["&lt;uuid&gt;"],"assigner":"&lt;uuid&gt;"}
     *   200 ONE object (not a list): {"id","processCode","entityId","action","currentState",
     *        "assignees","assigner","comment","documents","stateSla","processSla","auditDetail"}
     * </pre>
     *
     * <p>What the 2.x request carried and v3 has no field for: the {@code RequestInfo} envelope,
     * the {@code ProcessInstances} list wrapper, {@code tenantId} (a header now) and
     * {@code moduleName}. The last of those is worth a note in the baseline diff: CCRS sent "PGR"
     * and 2.x OVERWROTE it with the business service's {@code business}, "pgr-services". v3 has
     * neither field, so the reply now echoes what CCRS sent. Nothing in CCRS reads it back.
     *
     * <p>{@code assignes} are full 2.x User objects and v3 takes bare uuid STRINGS; CCRS only ever
     * populated the uuid, so nothing is lost outbound. {@code assigner} is sent because the field
     * exists and a later build may honour it — the deployed one accepts it and echoes "".
     *
     * <p><b>Roles.</b> The live v3 workflow service ENFORCES the {@code roles} declared on an
     * action ("caller role does not permit the requested action", 403) and reads them from exactly
     * one place: {@code Authorization: Bearer &lt;jwt&gt;.realm_access.roles}. The {@code X-Roles}
     * header named in the service's own Headers constant is ignored by the shipped build. CCRS
     * forwards the token the gateway issued and mints nothing — see {@link V3Headers}. That token
     * is today an opaque 2.x token, so every role-guarded action (which is all of them) is answered
     * 403 until the identity work lands. Verified 2026-09-21 against the live stack: an opaque uuid
     * bearer and a JWT without realm_access.roles are both 403; only a JWT carrying the roles is
     * 200. The failure is loud and carries v3's own text, which is the correct state of affairs for
     * a capability CCRS does not yet have.
     */
    private ProcessInstanceResponse transitionV3(ProcessInstanceRequest workflowReq) {
        List<ProcessInstance> processInstances = workflowReq.getProcessInstances();
        if (processInstances == null || processInstances.size() != 1)
            throw new CustomException("WORKFLOW_TRANSITION_BATCH_UNSUPPORTED",
                    "A workflow transition carried " + (processInstances == null ? 0 : processInstances.size())
                            + " process instances. 2.x accepted a list; DIGIT 3.0 POST /workflow/v3/transition takes "
                            + "exactly ONE entity per call. pgr-services always sends one, so this is a hard error "
                            + "rather than a partial batch, which would leave some complaints transitioned and some "
                            + "not with no way to tell which.");

        ProcessInstance processInstance = processInstances.get(0);
        refuseIfUnsupportedOnV3(processInstance.getAction(), processInstance.getBusinessId());
        RequestInfo requestInfo = workflowReq.getRequestInfo();
        String tenantId = V3Headers.workflowTenant(requestInfo, null, processInstance.getTenantId());
        String which = "POST " + pgrConfiguration.getWorkflowV3TransitionPath();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("processCode", StringUtils.isEmpty(processInstance.getBusinessService())
                ? PGR_BUSINESSSERVICE : processInstance.getBusinessService());
        body.put("entityId", processInstance.getBusinessId());
        body.put("action", processInstance.getAction());
        if (processInstance.getComment() != null)
            body.put("comment", processInstance.getComment());
        if (!CollectionUtils.isEmpty(processInstance.getDocuments()))
            body.put("documents", processInstance.getDocuments());
        if (!CollectionUtils.isEmpty(processInstance.getAssignes())) {
            List<String> assignees = processInstance.getAssignes().stream()
                    .map(User::getUuid)
                    .filter(uuid -> uuid != null && !uuid.trim().isEmpty())
                    .collect(Collectors.toList());
            if (!assignees.isEmpty())
                body.put("assignees", assignees);
        }
        String assigner = V3Headers.userIdOf(requestInfo);
        if (assigner != null)
            body.put("assigner", assigner);

        Map<String, String> headers = V3Headers.forTenant(tenantId)
                .withUserFrom(requestInfo)
                .withBearerFrom(requestInfo)
                .build();

        if (!headers.containsKey(V3Headers.AUTHORIZATION))
            log.error("Transitioning {} with action {} and NO Authorization header. DIGIT 3.0 reads action roles "
                            + "only from a Bearer token's realm_access.roles, so any action that declares roles - which "
                            + "is every PGR action - will be answered 403 Forbidden.",
                    processInstance.getBusinessId(), processInstance.getAction());

        Map<?, ?> response;
        try {
            response = repository.exchange(HttpMethod.POST,
                    joinUrl(pgrConfiguration.getWorkflowV3Host(), pgrConfiguration.getWorkflowV3TransitionPath()),
                    body, headers, Map.class);
        } catch (ServiceCallException e) {
            // A self-loop this build refuses that the configured list did not predict - a process
            // definition edited later, or a different module's definition. Same diagnosis, same
            // error code, rather than a bare transport failure nobody can read.
            if (isSelfLoopRefusal(e))
                throw unsupportedActionOnV3(processInstance.getAction(), processInstance.getBusinessId(),
                        "v3 refused it at runtime: " + e.getError());
            throw e;
        }

        if (response == null)
            throw new CustomException("WORKFLOW_RESPONSE_UNREADABLE",
                    "v3 " + which + " returned no body; the resulting state of complaint "
                            + processInstance.getBusinessId() + " is unknown");

        // v3 answers a transition with ONE object, not a list. The mapper throws if it carries no
        // state code: applicationStatus goes straight onto the complaint from here, and a null
        // there is HTTP 200 with a statusless complaint and nothing in any log.
        ProcessInstance transitioned = v3Mapper.processInstance(response, tenantId, PGR_BUSINESSSERVICE,
                processInstance.getModuleName(), V3Headers.userIdOf(requestInfo), which);

        return ProcessInstanceResponse.builder()
                .processInstances(Collections.singletonList(transitioned))
                .build();
    }


    public Set<String> getServiceRequestIdsByAssignee(RequestInfo requestInfo, String tenantId, String assigneeUuid) {
        List<ProcessInstance> processInstances;

        if (pgrConfiguration.isWorkflowV3()) {
            processInstances = searchProcessInstancesV3(requestInfo, tenantId, null, assigneeUuid, false);
        } else {
            StringBuilder url = getProcessInstanceSearchURLByAssignee(tenantId, assigneeUuid);
            RequestInfoWrapper requestInfoWrapper = RequestInfoWrapper.builder().requestInfo(requestInfo).build();
            Object result = repository.fetchResult(url, requestInfoWrapper);
            ProcessInstanceResponse response;
            try {
                response = mapper.convertValue(result, ProcessInstanceResponse.class);
            } catch (IllegalArgumentException e) {
                throw new CustomException("PARSING ERROR", "Failed to parse workflow response for assignee search");
            }
            processInstances = response.getProcessInstances();
        }

        if (CollectionUtils.isEmpty(processInstances)) {
            return Collections.emptySet();
        }

        return processInstances.stream()
                .map(ProcessInstance::getBusinessId)
                .collect(Collectors.toSet());
    }


}
