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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.util.CollectionUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;
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
public class WorkflowService {

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
     * <p>Until the fan-out lands, more than one id on v3 is refused outright rather than asked for
     * and half-answered.
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

        if (serviceRequestIds.size() > 1)
            throw new CustomException("WORKFLOW_MULTI_ENTITY_UNSUPPORTED",
                    "Asked the workflow service for " + serviceRequestIds.size() + " complaints in one search. "
                            + "DIGIT 3.0 GET /workflow/v3/transition serves exactly one entityId - the comma form "
                            + "answers totalCount 0 and the repeated form honours only the first value, both with "
                            + "HTTP 200. Refusing to send it: a short list here is an employee inbox missing "
                            + "complaints with no error anywhere.");

        return searchProcessInstancesV3(requestInfo, tenantId, serviceRequestIds.get(0), null, false);
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

        UriComponentsBuilder uri = UriComponentsBuilder
                .fromHttpUrl(joinUrl(pgrConfiguration.getWorkflowV3Host(), pgrConfiguration.getWorkflowV3TransitionPath()))
                .queryParam("processCode", PGR_BUSINESSSERVICE);
        if (!StringUtils.isEmpty(entityId))
            uri.queryParam("entityId", entityId);
        if (!StringUtils.isEmpty(assigneeUuid))
            uri.queryParam("assignee", assigneeUuid);
        if (history)
            uri.queryParam("history", "true");
        uri.queryParam("size", pgrConfiguration.getV3PageMaxSize());

        Map<String, String> headers = V3Headers.forTenant(workflowTenant(requestInfo, tenantId))
                .withUserFrom(requestInfo)
                .build();

        Map<?, ?> body = repository.exchange(HttpMethod.GET, uri.encode().toUriString(), null, headers, Map.class);

        List<Map<String, Object>> rows = v3Mapper.rowsOf(body, WorkflowV3Mapper.PROCESS_INSTANCES_KEY, which);
        v3Mapper.assertNotTruncated(v3Mapper.totalCountOf(body), rows.size(), which);

        List<ProcessInstance> processInstances = new ArrayList<>();
        for (Map<String, Object> row : rows)
            processInstances.add(v3Mapper.processInstance(row, tenantId, PGR_BUSINESSSERVICE,
                    PGR_MODULENAME, V3Headers.userIdOf(requestInfo), which));
        return processInstances;
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

        StringBuilder url = new StringBuilder(pgrConfiguration.getWfHost().concat(pgrConfiguration.getWfTransitionPath()));
        Object optional = repository.fetchResult(url, workflowReq);
        return mapper.convertValue(optional, ProcessInstanceResponse.class);
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
