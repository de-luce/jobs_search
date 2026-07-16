package com.getjobs.application.service;

import com.getjobs.application.utils.DeliveryStatuses;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 覆盖 51job jobId 解析：数值型 JSON 必须能读出，否则整页无法落库。
 */
class Job51ServiceReadLongTest {

    @Test
    void readLong_supportsNumericAndTextualJobId() throws Exception {
        Method m = Job51Service.class.getDeclaredMethod(
                "readLong", com.fasterxml.jackson.databind.JsonNode[].class);
        m.setAccessible(true);

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode numeric = mapper.readTree("{\"jobId\":171116257}").path("jobId");
        com.fasterxml.jackson.databind.JsonNode textual = mapper.readTree("{\"jobId\":\"171116257\"}").path("jobId");

        Long fromNumeric = (Long) m.invoke(null, (Object) new com.fasterxml.jackson.databind.JsonNode[]{numeric});
        Long fromTextual = (Long) m.invoke(null, (Object) new com.fasterxml.jackson.databind.JsonNode[]{textual});

        assertNotNull(fromNumeric);
        assertNotNull(fromTextual);
        assertEquals(171116257L, fromNumeric);
        assertEquals(171116257L, fromTextual);
        assertEquals(DeliveryStatuses.DELIVERED, DeliveryStatuses.normalize("已投递"));
    }
}
