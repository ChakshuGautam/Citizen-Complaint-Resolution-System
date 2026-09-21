package org.egov.pgr.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.egov.tracer.model.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link UserUtils#parseResponse} against the shapes a user-service response can legitimately
 * take once it is no longer guaranteed to be a 2.x egov-user reply.
 *
 * <p>The 2.x conversion is unchanged: a {@code dd-MM-yyyy HH:mm:ss} string still becomes epoch
 * millis and a string that will not parse still throws INVALID_DATE_FORMAT. What changes is that
 * an ABSENT or ALREADY-NUMERIC createdDate is now left alone instead of failing deep inside
 * pgr-services — it used to be the one date field converted without a null check, so it NPE'd
 * inside SimpleDateFormat.parse on a user object that carried no createdDate, and threw a
 * ClassCastException on the (String) cast when it carried an epoch. A DIGIT 3.0 individual reports
 * its create time as {@code auditDetail.createdTime} — epoch millis, or nothing at all — so both
 * are shapes this method must survive rather than shapes it should reject.
 */
class UserUtilsParseResponseTest {

    private UserUtils userUtils;

    @BeforeEach
    void setup() {
        userUtils = new UserUtils(new ObjectMapper(), null, null, null);
    }

    private static LinkedHashMap<String, Object> responseWithOneUser(LinkedHashMap<String, Object> user) {
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("user", List.of(user));
        return response;
    }

    private static long epochOf(String date, String format) throws Exception {
        return new SimpleDateFormat(format).parse(date).getTime();
    }

    @Test
    void convertsTheTwoXStringDatesToEpochMillis() throws Exception {
        LinkedHashMap<String, Object> user = new LinkedHashMap<>();
        user.put("createdDate", "21-09-2026 10:15:30");
        user.put("lastModifiedDate", "22-09-2026 11:16:31");
        user.put("dob", "1990-01-31");
        user.put("pwdExpiryDate", "23-09-2026 12:17:32");

        userUtils.parseResponse(responseWithOneUser(user), "yyyy-MM-dd");

        assertEquals(epochOf("21-09-2026 10:15:30", "dd-MM-yyyy HH:mm:ss"), user.get("createdDate"));
        assertEquals(epochOf("22-09-2026 11:16:31", "dd-MM-yyyy HH:mm:ss"), user.get("lastModifiedDate"));
        assertEquals(epochOf("1990-01-31", "yyyy-MM-dd"), user.get("dob"));
        assertEquals(epochOf("23-09-2026 12:17:32", "dd-MM-yyyy HH:mm:ss"), user.get("pwdExpiryDate"));
    }

    @Test
    void aUserWithoutACreatedDateIsLeftAloneRatherThanNpeing() {
        LinkedHashMap<String, Object> user = new LinkedHashMap<>();
        user.put("uuid", "629b9122-0000-0000-0000-000000000001");

        userUtils.parseResponse(responseWithOneUser(user), "yyyy-MM-dd");

        assertEquals("629b9122-0000-0000-0000-000000000001", user.get("uuid"));
        assertNull(user.get("createdDate"));
    }

    @Test
    void anExplicitlyNullCreatedDateIsLeftAloneRatherThanNpeing() {
        LinkedHashMap<String, Object> user = new LinkedHashMap<>();
        user.put("createdDate", null);

        userUtils.parseResponse(responseWithOneUser(user), "yyyy-MM-dd");

        assertTrue(user.containsKey("createdDate"));
        assertNull(user.get("createdDate"));
    }

    @Test
    void anAlreadyNumericCreatedDateIsKeptRatherThanCastToString() {
        LinkedHashMap<String, Object> user = new LinkedHashMap<>();
        user.put("createdDate", 1758448530000L);

        userUtils.parseResponse(responseWithOneUser(user), "yyyy-MM-dd");

        assertEquals(1758448530000L, user.get("createdDate"));
    }

    @Test
    void aDobWithNoResolvedFormatIsLeftUnparsedRatherThanNpeing() {
        LinkedHashMap<String, Object> user = new LinkedHashMap<>();
        user.put("createdDate", "21-09-2026 10:15:30");
        user.put("dob", "1990-01-31");

        userUtils.parseResponse(responseWithOneUser(user), null);

        assertEquals("1990-01-31", user.get("dob"));
    }

    @Test
    void anUnparseableCreatedDateStillFailsLoudly() {
        LinkedHashMap<String, Object> user = new LinkedHashMap<>();
        user.put("createdDate", "not-a-date");

        CustomException e = assertThrows(CustomException.class,
                () -> userUtils.parseResponse(responseWithOneUser(user), "yyyy-MM-dd"));

        assertEquals("INVALID_DATE_FORMAT", e.getCode());
    }
}
