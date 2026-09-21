package org.egov.pgr.util;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import lombok.extern.slf4j.Slf4j;
import org.egov.common.contract.request.RequestInfo;
import org.egov.mdms.model.MdmsCriteriaReq;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.egov.pgr.service.WorkflowService;
import org.egov.pgr.web.models.RequestInfoWrapper;
import org.egov.pgr.web.models.User;
import org.egov.pgr.web.models.user.UserDetailResponse;
import org.egov.pgr.web.models.user.UserSearchRequest;
import org.egov.pgr.web.models.workflow.BusinessService;
import org.egov.pgr.web.models.workflow.State;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.egov.pgr.util.PGRConstants.*;

@Slf4j
@Component
public class MigrationUtils {


    private UserUtils userUtils;

    private PGRConfiguration config;

    private ObjectMapper mapper;

    private ServiceRequestRepository repository;

    private MDMSUtils mdmsUtils;

    private WorkflowService workflowService;

    @Autowired
    public MigrationUtils(UserUtils userUtils, PGRConfiguration config, ObjectMapper mapper, ServiceRequestRepository repository, MDMSUtils mdmsUtils, WorkflowService workflowService) {
        this.userUtils = userUtils;
        this.config = config;
        this.mapper = mapper;
        this.repository = repository;
        this.mdmsUtils = mdmsUtils;
        this.workflowService = workflowService;
    }





    public Map<Long, String> getIdtoUUIDMap(List<String> ids) {

        /**
         * calls the user search API based on the given list of user uuids
         * @param uuids
         * @return
         */

        ids.removeAll(Collections.singleton(null));

        UserSearchRequest userSearchRequest = new UserSearchRequest();

        if (!CollectionUtils.isEmpty(ids))
            userSearchRequest.setId(ids);

        StringBuilder uri = new StringBuilder(config.getUserHost()).append(config.getUserSearchEndpoint());
        UserDetailResponse userDetailResponse = userUtils.userCall(userSearchRequest, uri);
        List<User> users = userDetailResponse.getUser();

        if (CollectionUtils.isEmpty(users))
            throw new CustomException("USER_NOT_FOUND", "No user found for the uuids");

        Map<Long, String> idToUuidMap = users.stream().collect(Collectors.toMap(User::getId, User::getUuid));

        if (idToUuidMap.keySet().size() != ids.size())
            throw new CustomException("UUID_NOT_FOUND", "Number of ids searched: " + ids.size() + " uuids returned: " + idToUuidMap.keySet().size());

        return idToUuidMap;

    }




    /**
     * status code -> state uuid, for the 2.x migration.
     *
     * <p>The definition search itself now lives in {@link WorkflowService}, which owns both stacks;
     * this class used to keep a second copy of the URL builder and a second copy of the parse, and
     * two copies of a call that is about to change shape is one too many.
     *
     * <p>The null-key skip below is not cosmetic. The 2.x PGR start state is NAMELESS, so its
     * {@code state} is null, and {@code Collectors.toMap} throws NullPointerException on a null
     * key. Under DIGIT 3.0 that state is certain to appear: v3 forbids an empty state code, the
     * registered definition calls it START, and the mapper turns START back into the 2.x null. The
     * start state has no status to key, so it is skipped rather than allowed to NPE.
     */
    public Map<String,String> getStatusToUUIDMap(String tenantId) {
        BusinessService businessService = workflowService.getBusinessService(new RequestInfo(), tenantId);

        if (CollectionUtils.isEmpty(businessService.getStates()))
            throw new CustomException("BUSINESSSERVICE_NOT_FOUND", "The businessService " + PGR_BUSINESSSERVICE + " has no states");

        Map<String,String> statusToUUIDMap = new HashMap<>();
        for (State state : businessService.getStates())
            if (state.getState() != null)
                statusToUUIDMap.put(state.getState(), state.getUuid());

        return statusToUUIDMap;
    }


    public Map<String,Long> getServiceCodeToSLAMap(String tenantId) {

        Map<String, Long> serviceCodeToSLA = new HashMap<>();

        MdmsCriteriaReq mdmsCriteriaReq = mdmsUtils.getMDMSRequest(new RequestInfo(),tenantId);
        Object result = repository.fetchResult(mdmsUtils.getMdmsSearchUrl(), mdmsCriteriaReq);
        List<Map<String, Object>> res = new LinkedList<>();


        try{
            res = JsonPath.read(result,MDMS_DATA_JSONPATH);
        }
        catch (Exception e){
            throw new CustomException("JSONPATH_ERROR","Failed to parse mdms response");
        }

        for(Map<String, Object> map : res){
            Object slaRaw = map.get(MDMS_DATA_SLA_KEYWORD);
            Object codeRaw = map.get(MDMS_DATA_SERVICE_CODE_KEYWORD);
            // Interior ComplaintHierarchy nodes carry no slaHours — only leaf rows do. Skip
            // non-leaf rows so we don't NPE unboxing a null slaHours.
            if (!(slaRaw instanceof Number) || codeRaw == null)
                continue;
            Long SLA = TimeUnit.HOURS.toMillis(((Number) slaRaw).longValue());
            serviceCodeToSLA.put((String) codeRaw, SLA);
        }

        return serviceCodeToSLA;
    }



}
