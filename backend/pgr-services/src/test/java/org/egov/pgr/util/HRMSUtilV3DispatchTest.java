package org.egov.pgr.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.egov.common.contract.request.RequestInfo;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.identity.EmployeeV3Client;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which registry {@code HRMSUtil} asks, on each stack, and what the answer can and cannot contain.
 */
class HRMSUtilV3DispatchTest {

    private static final String TENANT = "pg";
    private static final String EMPLOYEE_UUID = "21fc9b81-9e52-43a5-ac1a-00db84dc2a6a";

    private ServiceRequestRepository serviceRequestRepository;
    private EmployeeV3Client employeeV3Client;
    private PGRConfiguration config;
    private HRMSUtil hrmsUtil;

    @BeforeEach
    void setUp() {
        serviceRequestRepository = mock(ServiceRequestRepository.class);
        employeeV3Client = mock(EmployeeV3Client.class);
        config = new PGRConfiguration();
        config.setHrmsHost("http://hrms:8092");
        config.setHrmsEndPoint("/egov-hrms/employees/_search");
        hrmsUtil = new HRMSUtil(serviceRequestRepository, config, employeeV3Client);
    }

    /** The 2.x HRMS shape, which the v3 client also answers in so the JsonPaths do not move. */
    private ObjectNode employees(String department, String reportingTo) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode assignment = mapper.createObjectNode();
        assignment.put("isCurrentAssignment", true);
        assignment.put("department", department);
        if (reportingTo == null) assignment.putNull("reportingTo");
        else assignment.put("reportingTo", reportingTo);
        ObjectNode employee = mapper.createObjectNode();
        employee.put("uuid", EMPLOYEE_UUID);
        employee.putArray("assignments").add(assignment);
        root.putArray("Employees").add(employee);
        return root;
    }

    @Test
    @DisplayName("2.x: the department still comes from a POST to egov-hrms")
    void twoXDepartment() {
        config.setHrmsApiVersion(PGRConfiguration.API_VERSION_2X);
        when(serviceRequestRepository.fetchResult(any(), any()))
                .thenReturn(new ObjectMapper().convertValue(employees("SANITATION", "boss-uuid"), Map.class));

        List<String> departments = hrmsUtil.getDepartment(
                Collections.singletonList(EMPLOYEE_UUID), new RequestInfo(), TENANT);

        assertEquals(Collections.singletonList("SANITATION"), departments);
        verify(employeeV3Client, never()).searchByLegacyUuids(anyCollection(), anyString(), any());
    }

    @Test
    @DisplayName("v3: the department comes from d3-employee, in the same shape, so the JsonPath is untouched")
    void v3Department() {
        config.setHrmsApiVersion(PGRConfiguration.API_VERSION_V3);
        when(employeeV3Client.searchByLegacyUuids(anyCollection(), eq(TENANT), any()))
                .thenReturn(employees("SANITATION", null));

        List<String> departments = hrmsUtil.getDepartment(
                Collections.singletonList(EMPLOYEE_UUID), new RequestInfo(), TENANT);

        assertEquals(Collections.singletonList("SANITATION"), departments);
        verify(serviceRequestRepository, never()).fetchResult(any(), any());
    }

    @Test
    @DisplayName("v3: there is no supervisor, so getSupervisorUuid answers null rather than inventing one")
    void v3SupervisorIsAbsent() {
        config.setHrmsApiVersion(PGRConfiguration.API_VERSION_V3);
        when(employeeV3Client.searchByLegacyUuids(anyCollection(), eq(TENANT), any()))
                .thenReturn(employees("SANITATION", null));

        assertNull(hrmsUtil.getSupervisorUuid(EMPLOYEE_UUID, new RequestInfo(), TENANT));
    }

    @Test
    @DisplayName("2.x: the supervisor edge still comes from the current assignment's reportingTo")
    void twoXSupervisor() {
        config.setHrmsApiVersion(PGRConfiguration.API_VERSION_2X);
        when(serviceRequestRepository.fetchResult(any(), any()))
                .thenReturn(new ObjectMapper().convertValue(employees("SANITATION", "boss-uuid"), Map.class));

        assertEquals("boss-uuid", hrmsUtil.getSupervisorUuid(EMPLOYEE_UUID, new RequestInfo(), TENANT));
    }
}
