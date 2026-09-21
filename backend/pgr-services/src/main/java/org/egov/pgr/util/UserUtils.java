package org.egov.pgr.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.Role;
import org.egov.common.utils.MultiStateInstanceUtil;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.identity.IndividualV3Client;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.egov.pgr.web.models.User;
import org.egov.pgr.web.models.user.CreateUserRequest;
import org.egov.pgr.web.models.user.UserDetailResponse;
import org.egov.pgr.web.models.user.UserSearchRequest;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;

import static org.egov.pgr.util.PGRConstants.USERTYPE_CITIZEN;

@Component
@Slf4j
public class UserUtils {


    private ObjectMapper mapper;

    private ServiceRequestRepository serviceRequestRepository;

    private PGRConfiguration config;

    private IndividualV3Client individualV3Client;

    @Autowired
    private MultiStateInstanceUtil centralInstanceUtil;

    @Autowired
    public UserUtils(ObjectMapper mapper, ServiceRequestRepository serviceRequestRepository, PGRConfiguration config,
                     IndividualV3Client individualV3Client) {
        this.mapper = mapper;
        this.serviceRequestRepository = serviceRequestRepository;
        this.config = config;
        this.individualV3Client = individualV3Client;
    }

    /**
     * Returns UserDetailResponse by calling user service with given uri and object
     * @param userRequest Request object for user service
     * @param uri The address of the endpoint
     * @return Response from user service as parsed as userDetailResponse
     */

    public UserDetailResponse userCall(Object userRequest, StringBuilder uri) {
        if (config != null && config.isUserV3())
            return individualCall(userRequest, uri);

        String dobFormat = null;
        if(uri.toString().contains(config.getUserSearchEndpoint())  || uri.toString().contains(config.getUserUpdateEndpoint()))
            dobFormat="yyyy-MM-dd";
        else if(uri.toString().contains(config.getUserCreateEndpoint()))
            dobFormat = "dd/MM/yyyy";
        try{
            LinkedHashMap responseMap = (LinkedHashMap)serviceRequestRepository.fetchResult(uri, userRequest);
            parseResponse(responseMap,dobFormat);
            UserDetailResponse userDetailResponse = mapper.convertValue(responseMap,UserDetailResponse.class);
            return userDetailResponse;
        }
        catch(IllegalArgumentException  e)
        {
            throw new CustomException("IllegalArgumentException","ObjectMapper not able to convertValue in userCall");
        }
    }


    /**
     * The same funnel, answered by the DIGIT 3.0 individual registry.
     *
     * <p>Every 2.x user path in this service goes through {@link #userCall}, so the branch is here
     * rather than at six call sites: each caller keeps its own shape and its own assertions, and
     * the 2.x wire call above stays byte-identical so a deployment that flips and finds the new
     * stack wrong gets yesterday's behaviour back by moving one property.
     *
     * <p>The translation is deliberately NARROW. 2.x sends filters on every search - userType,
     * active, a page number, a role code - and v3 accepts all of them and applies none, answering
     * 200 with the whole tenant. Sending a filter that does not filter is worse than sending none,
     * so a search this method cannot serve with a filter it has watched work is refused by name
     * rather than widened.
     */
    private UserDetailResponse individualCall(Object userRequest, StringBuilder uri) {
        if (userRequest instanceof UserSearchRequest)
            return individualSearch((UserSearchRequest) userRequest);

        if (userRequest instanceof CreateUserRequest)
            throw new CustomException("USER_WRITE_UNSUPPORTED_ON_V3",
                    "Creating or updating a person in the DIGIT 3.0 individual registry is not wired yet. "
                            + "It is not a re-point: create rejects userName, type and roles as unknown fields and "
                            + "demands a gender CCRS's user model does not have, and update is optimistic-"
                            + "concurrency - a PUT with no record version is 409 ROW_VERSION_MISMATCH, so it needs "
                            + "a read first. Refusing rather than writing half a person.");

        throw new CustomException("USER_REQUEST_UNSUPPORTED_ON_V3",
                "Unrecognised user request of type " + userRequest.getClass().getName() + " for " + uri);
    }

    private UserDetailResponse individualSearch(UserSearchRequest request) {
        String tenantId = request.getTenantId();
        RequestInfo requestInfo = request.getRequestInfo();

        if (!CollectionUtils.isEmpty(request.getRoleCodes()))
            throw new CustomException("USER_FILTER_UNSUPPORTED_ON_V3",
                    "A role-filtered user search has no DIGIT 3.0 expression: roles are Keycloak realm roles and "
                            + "the individual registry has no roles field and no role filter.");

        if (!CollectionUtils.isEmpty(request.getUuid()))
            return new UserDetailResponse(null,
                    individualV3Client.searchByLegacyUuids(request.getUuid(), tenantId, requestInfo));

        String mobileNumber = request.getMobileNumber();
        if (mobileNumber == null || mobileNumber.trim().isEmpty()) {
            // 2.x sets userName = mobileNumber for every citizen it creates
            // (addUserDefaultFields below), and v3 rejects userName outright as an unknown field.
            // So a CITIZEN search by userName IS a search by mobile number, and that equivalence is
            // the only reason this is a translation rather than a guess. It does not hold for an
            // employee, whose userName is a staff code - hence the pin.
            String userName = request.getUserName();
            if (userName != null && !userName.trim().isEmpty()) {
                if (!USERTYPE_CITIZEN.equalsIgnoreCase(request.getUserType()))
                    throw new CustomException("USER_FILTER_UNSUPPORTED_ON_V3",
                            "A search by userName is only translatable for a CITIZEN, where 2.x set userName to the "
                                    + "mobile number. This search asks for userType=" + request.getUserType()
                                    + ", whose userName is a staff code that the individual registry has never held.");
                mobileNumber = userName;
            }
        }

        if (mobileNumber != null && !mobileNumber.trim().isEmpty())
            return new UserDetailResponse(null,
                    individualV3Client.searchByMobileNumber(mobileNumber, tenantId, requestInfo));

        throw new CustomException("USER_FILTER_UNSUPPORTED_ON_V3",
                "A user search with no uuid, mobile number or userName cannot be served on DIGIT 3.0. Only id, "
                        + "individualId and mobileNumber are filters the shipped build actually applies; userType, "
                        + "active and userId are accepted and ignored, and an unfiltered search answers 200 with "
                        + "every individual in the tenant.");
    }

/**
 * Parses date formats to long for all users in responseMap
 * @param responeMap LinkedHashMap got from user api response
 */

    public void parseResponse(LinkedHashMap responeMap, String dobFormat){
        List<LinkedHashMap> users = (List<LinkedHashMap>)responeMap.get("user");
        String format1 = "dd-MM-yyyy HH:mm:ss";
        if(users!=null){
            users.forEach( map -> {
                        putDateAsLong(map,"createdDate",format1);
                        putDateAsLong(map,"lastModifiedDate",format1);
                        putDateAsLong(map,"dob",dobFormat);
                        putDateAsLong(map,"pwdExpiryDate",format1);
                    }
            );
        }
    }

    /**
     * Converts one date field of a user map from its 2.x string form to epoch millis, in place.
     *
     * createdDate used to be converted unguarded, unlike the three fields beneath it, so a user
     * object that carried no createdDate - or carried one that was not a String - failed inside
     * pgr-services rather than at the boundary: a null went into SimpleDateFormat.parse and came
     * back out as an NPE, and a numeric epoch blew up on the (String) cast before it ever reached
     * the parser. Both are shapes a differently-shaped user response legitimately has (a v3
     * individual reports auditDetail.createdTime as epoch millis, or not at all), so neither is a
     * parse error. An absent, null, blank or already-numeric value is therefore left exactly as it
     * is; only a non-blank String is parsed, and a String that will not parse still fails loudly
     * with INVALID_DATE_FORMAT, which is the one case that really is bad input.
     *
     * @param map one user object from the user service response
     * @param field the date field to convert
     * @param format the expected date format, or null if the caller could not determine one
     */
    private void putDateAsLong(LinkedHashMap map, String field, String format){
        Object value = map.get(field);
        if(!(value instanceof String) || ((String)value).trim().isEmpty())
            return;
        if(format==null){
            log.warn("No date format resolved for user field '{}'; leaving it unparsed", field);
            return;
        }
        map.put(field,dateTolong((String)value,format));
    }

    /**
     * Converts date to long
     * @param date date to be parsed
     * @param format Format of the date
     * @return Long value of date
     */
    private Long dateTolong(String date,String format){
        SimpleDateFormat f = new SimpleDateFormat(format);
        Date d = null;
        try {
            d = f.parse(date);
        } catch (ParseException e) {
            throw new CustomException("INVALID_DATE_FORMAT","Failed to parse date format in user");
        }
        return  d.getTime();
    }

    /**
     * enriches the userInfo with statelevel tenantId and other fields
     * @param mobileNumber
     * @param tenantId
     * @param userInfo
     */
    public void addUserDefaultFields(String mobileNumber,String tenantId, User userInfo){
        Role role = getCitizenRole(tenantId);
        userInfo.setRoles(Collections.singletonList(role));
        userInfo.setType("CITIZEN");
        userInfo.setUserName(mobileNumber);
        userInfo.setTenantId(getStateLevelTenant(tenantId));
        userInfo.setActive(true);
    }

    /**
     * Returns role object for citizen
     * @param tenantId
     * @return
     */
    private Role getCitizenRole(String tenantId){
        Role role = new Role();
        role.setCode("CITIZEN");
        role.setName("Citizen");
        role.setTenantId(getStateLevelTenant(tenantId));
        return role;
    }

    public String getStateLevelTenant(String tenantId){
       /* return tenantId.split("\\.")[0];*/
        log.info("tenantId"+ tenantId);
        return centralInstanceUtil.getStateLevelTenant(tenantId);
    }



}
