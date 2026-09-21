package org.egov.pgr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.User;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.egov.pgr.web.models.*;
import org.egov.pgr.web.models.workflow.*;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;

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

    @Autowired
    public WorkflowService(PGRConfiguration pgrConfiguration, ServiceRequestRepository repository, ObjectMapper mapper) {
        this.pgrConfiguration = pgrConfiguration;
        this.repository = repository;
        this.mapper = mapper;
    }

    /*
     *
     * Should return the applicable BusinessService for the given request
     *
     * */
    public BusinessService getBusinessService(ServiceRequest serviceRequest) {
        String tenantId = serviceRequest.getService().getTenantId();
        StringBuilder url = getBusinessServiceSearchURL(tenantId);
        RequestInfoWrapper requestInfoWrapper = RequestInfoWrapper.builder().requestInfo(serviceRequest.getRequestInfo()).build();
        Object result = repository.fetchResult(url, requestInfoWrapper);
        BusinessServiceResponse response = parseBusinessServices(result);

        if (CollectionUtils.isEmpty(response.getBusinessServices()))
            throw new CustomException("BUSINESSSERVICE_NOT_FOUND", "The businessService " + PGR_BUSINESSSERVICE + " is not found");

        return response.getBusinessServices().get(0);
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
     * <p>Pulled out of {@link #enrichWorkflow} so that the inbox join has one seam: 2.x serves
     * every id in a single call with a comma-separated {@code businessIds}, and DIGIT 3.0 serves
     * exactly one {@code entityId} per call.
     */
    private List<ProcessInstance> searchProcessInstances(RequestInfo requestInfo, String tenantId,
                                                         List<String> serviceRequestIds) {
        RequestInfoWrapper requestInfoWrapper = RequestInfoWrapper.builder().requestInfo(requestInfo).build();
        StringBuilder searchUrl = getprocessInstanceSearchURL(tenantId, StringUtils.join(serviceRequestIds, ','));
        Object result = repository.fetchResult(searchUrl, requestInfoWrapper);
        return parseProcessInstances(result).getProcessInstances();
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
        StringBuilder url = getProcessInstanceSearchURLByAssignee(tenantId, assigneeUuid);

        RequestInfoWrapper requestInfoWrapper = RequestInfoWrapper.builder().requestInfo(requestInfo).build();
        Object result = repository.fetchResult(url, requestInfoWrapper);

        ProcessInstanceResponse response;
        try {
            response = mapper.convertValue(result, ProcessInstanceResponse.class);
        } catch (IllegalArgumentException e) {
            throw new CustomException("PARSING ERROR", "Failed to parse workflow response for assignee search");
        }

        if (CollectionUtils.isEmpty(response.getProcessInstances())) {
            return Collections.emptySet();
        }

        return response.getProcessInstances().stream()
                .map(ProcessInstance::getBusinessId)
                .collect(Collectors.toSet());
    }


}
