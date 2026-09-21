package org.egov.pgr.util;

import com.jayway.jsonpath.JsonPath;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.egov.common.contract.request.RequestInfo;
import org.egov.common.utils.MultiStateInstanceUtil;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.producer.Producer;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.egov.pgr.web.models.Notification.EventRequest;
import org.egov.pgr.web.models.Notification.SMSRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;
import static org.egov.pgr.util.PGRConstants.*;

@Component
@Slf4j
public class NotificationUtil {

    @Autowired
    private ServiceRequestRepository serviceRequestRepository;

    @Autowired
    private PGRConfiguration config;

    @Autowired
    private Producer producer;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private MultiStateInstanceUtil centralInstanceUtil;


    /**
     *
     * @param tenantId Tenant ID
     * @param requestInfo Request Info object
     * @param module Module name
     * @return Return Localisation Message
     */
    public String getLocalizationMessages(String tenantId, RequestInfo requestInfo,String module) {
        if (config.isLocalizationV3())
            return getLocalizationMessagesV3(tenantId, requestInfo, module);
        @SuppressWarnings("rawtypes")
        LinkedHashMap responseMap = (LinkedHashMap) serviceRequestRepository.fetchResult(getUri(tenantId, requestInfo, module),
                requestInfo);
        return new JSONObject(responseMap).toString();
    }

    /**
     * The DIGIT 3.0 form of the same lookup.
     *
     * <p>Three differences from 2.x, and only three: the verb is GET, the tenant travels in
     * X-Tenant-ID instead of {@code ?tenantId=}, and there is no RequestInfo body — v3 reads
     * nothing from a body on a read and a body makes the request malformed. The response shape is
     * unchanged ({@code {"messages":[{code,message,module,locale}]}}, plus an inert {@code uuid}
     * that FAIL_ON_UNKNOWN_PROPERTIES=false ignores), so every caller of this method and the
     * JsonPath in {@link #getCustomizedMsgForPlaceholder} are untouched. Probed live against
     * d3-localization: a filtered search returns only the matching rows and an unknown locale or
     * module correctly returns an empty list.
     *
     * <p>Resolved at the STATE-level tenant, exactly as 2.x resolves it.
     */
    private String getLocalizationMessagesV3(String tenantId, RequestInfo requestInfo, String module) {
        String stateLevelTenant = centralInstanceUtil.getStateLevelTenant(tenantId);
        log.info("tenantId after calling central instance method :" + stateLevelTenant);
        @SuppressWarnings("rawtypes")
        Map responseMap = serviceRequestRepository.exchange(HttpMethod.GET,
                getV3Uri(stateLevelTenant, requestInfo, module), null,
                V3Headers.forTenant(stateLevelTenant).build(), Map.class);
        return new JSONObject(responseMap == null ? Collections.emptyMap() : responseMap).toString();
    }

    /**
     * Builds the v3 localization URI. Already encoded when it leaves here, because
     * {@code ServiceRequestRepository.exchange} passes it to {@code URI.create} with no template
     * expansion.
     *
     * @param stateLevelTenantId the STATE-level tenant — carried only so that it is NOT put on the
     *                           query string; it belongs in X-Tenant-ID
     */
    public String getV3Uri(String stateLevelTenantId, RequestInfo requestInfo, String module) {
        return UriComponentsBuilder
                .fromHttpUrl(joinUrl(config.getLocalizationV3Host(), config.getLocalizationV3SearchEndpoint()))
                .queryParam("locale", localeOf(requestInfo))
                .queryParam("module", module)
                .encode()
                .toUriString();
    }

    private static String joinUrl(String host, String endpoint) {
        if (host.endsWith("/") && endpoint.startsWith("/"))
            return host + endpoint.substring(1);
        if (!host.endsWith("/") && !endpoint.startsWith("/"))
            return host + "/" + endpoint;
        return host + endpoint;
    }

    /**
     * The locale the caller asked for, read off {@code RequestInfo.msgId} as {@code <id>|<locale>}.
     * Lifted verbatim out of {@link #getUri} so that the 2.x and v3 URI builders cannot drift
     * apart; the behaviour, quirks included, is unchanged.
     */
    private String localeOf(RequestInfo requestInfo) {
        String locale = NOTIFICATION_LOCALE;
        if (!StringUtils.isEmpty(requestInfo.getMsgId()) && requestInfo.getMsgId().split("|").length >= 2)
            locale = requestInfo.getMsgId().split("\\|")[1];
        return locale;
    }

    /**
     *
     * @param tenantId Tenant ID
     * @param requestInfo Request Info object
     * @param module Module name
     * @return Return uri
     */
    public StringBuilder getUri(String tenantId, RequestInfo requestInfo, String module) {

        /*if (config.getIsLocalizationStateLevel())
            tenantId= centralInstanceUtil.getStateLevelTenant(tenantId);*/
        tenantId= centralInstanceUtil.getStateLevelTenant(tenantId);
        log.info("tenantId after calling central instance method :"+ tenantId);
        String locale = localeOf(requestInfo);
        StringBuilder uri = new StringBuilder();
        uri.append(config.getLocalizationHost()).append(config.getLocalizationContextPath())
                .append(config.getLocalizationSearchEndpoint()).append("?").append("locale=").append(locale)
                .append("&tenantId=").append(tenantId).append("&module=").append(module);

        return uri;
    }

    /**
     *
     * @param action Action
     * @param applicationStatus Application Status
     * @param roles CITIZEN or EMPLOYEE
     * @param localizationMessage Localisation Message
     * @return Return Customized Message based on localisation code
     */
    public String getCustomizedMsg(String action, String applicationStatus, String roles, String localizationMessage) {
        StringBuilder notificationCode = new StringBuilder();

        notificationCode.append("PGR_").append(roles.toUpperCase()).append("_").append(action.toUpperCase()).append("_").append(applicationStatus.toUpperCase()).append("_SMS_MESSAGE");

        String path = "$..messages[?(@.code==\"{}\")].message";
        path = path.replace("{}", notificationCode);
        String message = null;
        try {
            ArrayList<String> messageObj = JsonPath.parse(localizationMessage).read(path);
            if(messageObj != null && messageObj.size() > 0) {
                message = messageObj.get(0);
            }
        } catch (Exception e) {
            log.warn("Fetching from localization failed", e);
        }

        return message;
    }

    /**
     *
     * @param roles EMPLOYEE or CITIZEN
     * @param localizationMessage Localisation Message
     * @return Return localisation message based on default code
     */
    public String getDefaultMsg(String roles, String localizationMessage) {
        StringBuilder notificationCode = new StringBuilder();

        notificationCode.append("PGR_").append("DEFAULT_").append(roles.toUpperCase()).append("_SMS_MESSAGE");

        String path = "$..messages[?(@.code==\"{}\")].message";
        path = path.replace("{}", notificationCode);
        String message = null;
        try {
            ArrayList<String> messageObj = JsonPath.parse(localizationMessage).read(path);
            if(messageObj != null && messageObj.size() > 0) {
                message = messageObj.get(0);
            }
        } catch (Exception e) {
            log.warn("Fetching from localization failed", e);
        }

        return message;
    }

    /**
     * Send the SMSRequest on the SMSNotification kafka topic
     * @param smsRequestList The list of SMSRequest to be sent
     */
    public void sendSMS(String tenantId, List<SMSRequest> smsRequestList) {
        if (config.getIsSMSEnabled()) {
            if (CollectionUtils.isEmpty(smsRequestList)) {
                log.info("Messages from localization couldn't be fetched!");
                return;
            }
            for (SMSRequest smsRequest : smsRequestList) {
                producer.push(tenantId,config.getSmsNotifTopic(), smsRequest);
                log.info("Messages: " + smsRequest.getMessage());
            }
        }
    }

    /**
     * Pushes the event request to Kafka Queue.
     *
     * @param request EventRequest Object
     */
    public void sendEventNotification(String tenantId, EventRequest request) {
        producer.push(tenantId,config.getSaveUserEventsTopic(), request);
    }

    /**
     *
     * @param actualURL Actual URL
     * @return Shortened URL
     */
    public String getShortnerURL(String actualURL) {
        HashMap<String,String> body = new HashMap<>();
        body.put("url",actualURL);
        StringBuilder builder = new StringBuilder(config.getUrlShortnerHost());
        builder.append(config.getUrlShortnerEndpoint());
        String res = restTemplate.postForObject(builder.toString(), body, String.class);

        if(StringUtils.isEmpty(res)){
            log.error("URL_SHORTENING_ERROR","Unable to shorten url: "+actualURL); ;
            return actualURL;
        }
        else return res;
    }

    /**
     *
     * @param localizationMessage Localisation Code
     * @param notificationCode Notification Code
     * @return Return Customized Message
     */
    public String    getCustomizedMsgForPlaceholder(String localizationMessage,String notificationCode) {
        String path = "$..messages[?(@.code==\"{}\")].message";
        path = path.replace("{}", notificationCode);
        String message = null;
        try {
            ArrayList<String> messageObj = (ArrayList<String>) JsonPath.parse(localizationMessage).read(path);
            if(messageObj != null && messageObj.size() > 0) {
                message = messageObj.get(0);
            }
        } catch (Exception e) {
            log.warn("Fetching from localization for placeholder failed", e);
        }
        return message;
    }

}
