package org.egov.pgr.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the one property of the per-dependency version flags that matters until the flips begin:
 * every one of them defaults to 2.x, so adding the flags changed nothing.
 *
 * <p>The second test reads the shipped application.properties rather than the Java defaults,
 * because that file is what a deployment actually gets and a flag flipped there by accident - in a
 * merge, or by copying a branch's file - would move a live tenant onto a v3 endpoint with no code
 * review of the move.
 */
class PGRConfigurationV3FlagsTest {

    private static final List<String> VERSION_FLAGS = List.of(
            "egov.idgen.api.version",
            "egov.boundary.api.version",
            "egov.workflow.api.version",
            "egov.user.api.version",
            "egov.localization.api.version",
            "egov.hrms.api.version",
            "egov.url.shortner.api.version");

    @Test
    void anUnsetFlagIsNotV3() {
        PGRConfiguration config = new PGRConfiguration();

        assertFalse(config.isIdgenV3());
        assertFalse(config.isBoundaryV3());
        assertFalse(config.isWorkflowV3());
        assertFalse(config.isUserV3());
        assertFalse(config.isLocalizationV3());
        assertFalse(config.isHrmsV3());
        assertFalse(config.isUrlShortnerV3());
    }

    @Test
    void onlyV3SelectsV3() {
        PGRConfiguration config = new PGRConfiguration();

        config.setWorkflowApiVersion("v3");
        assertTrue(config.isWorkflowV3());
        config.setWorkflowApiVersion(" V3 ");
        assertTrue(config.isWorkflowV3());

        config.setWorkflowApiVersion("2.x");
        assertFalse(config.isWorkflowV3());
        config.setWorkflowApiVersion("3.0");
        assertFalse(config.isWorkflowV3());
        config.setWorkflowApiVersion("");
        assertFalse(config.isWorkflowV3());
    }

    @Test
    void everyShippedFlagDefaultsToTwoX() throws Exception {
        Properties properties = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            assertNotNull(in, "application.properties is not on the test classpath");
            properties.load(in);
        }

        for (String flag : VERSION_FLAGS) {
            String value = properties.getProperty(flag);
            assertNotNull(value, flag + " is missing from application.properties");
            assertTrue(value.endsWith(":2.x}"),
                    flag + " must default to 2.x so that adding the flag changes no behaviour, but is: " + value);
        }
    }

    @Test
    void theV3PathsAreTheShapesTheShimProvedAgainstTheRunningStack() throws Exception {
        Properties properties = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            properties.load(in);
        }

        assertEquals("/idgen/v3/generate", properties.getProperty("egov.idgen.v3.generate.path"));
        assertEquals("/boundary/v3/boundaries", properties.getProperty("egov.boundary.v3.search.url"));
        assertEquals("/boundary/v3/relationship", properties.getProperty("egov.boundary.v3.relationship.search.url"));
        assertEquals("/workflow/v3/transition", properties.getProperty("egov.workflow.v3.transition.path"));
        assertEquals("/workflow/v3/process/definition", properties.getProperty("egov.workflow.v3.processdefinition.search.path"));
        assertEquals("/individual/v3/individuals", properties.getProperty("egov.user.v3.individual.path"));
        assertEquals("/localization/v3/messages", properties.getProperty("egov.localization.v3.search.endpoint"));
        assertEquals("/employee/v3/employees", properties.getProperty("egov.hrms.v3.employee.search.endpoint"));
        assertEquals("/url-shortener/v3/short-url", properties.getProperty("egov.url.shortner.v3.short.url.endpoint"));
        assertEquals("100", properties.getProperty("egov.v3.page.max.size"));

        // The one knob whose shipped value must be EMPTY. v3 demands a gender on individual-create
        // and offers only MALE/FEMALE/OTHER; CCRS's user model has no gender field, so any value
        // here is a fact invented about every citizen CCRS registers. A merge that quietly filled
        // it in would turn a loud refusal into a silent default, which is the failure mode this
        // whole migration exists to eliminate.
        String defaultGender = properties.getProperty("egov.user.v3.individual.default.gender");
        assertTrue(defaultGender == null || defaultGender.trim().isEmpty()
                        || defaultGender.trim().equals("${EGOV_USER_V3_INDIVIDUAL_DEFAULT_GENDER:}"),
                "egov.user.v3.individual.default.gender must ship BLANK: a value here invents a gender "
                        + "for every citizen CCRS creates on DIGIT 3.0, got '" + defaultGender + "'");
    }
}
