package com.getjobs.worker.job51;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Job51SearchApiTest {

    @Test
    void searchPc_getAndPost_shouldMatch() {
        String url = "https://we.51job.com/api/job/search-pc?keyword=java";
        assertTrue(Job51SearchApi.isSearchPcRequest(url, "GET"));
        assertTrue(Job51SearchApi.isSearchPcRequest(url, "POST"));
        assertTrue(Job51SearchApi.isSearchPcRequest(url, null));
    }

    @Test
    void otherApi_shouldNotMatch() {
        assertFalse(Job51SearchApi.isSearchPcRequest("https://we.51job.com/api/job/detail", "GET"));
        assertFalse(Job51SearchApi.isSearchPcRequest(null, "GET"));
    }

    @Test
    void jsonBody_detectedWithoutContentType() {
        assertTrue(Job51SearchApi.looksLikeJsonBody(null, " {\"data\":[]} "));
        assertTrue(Job51SearchApi.looksLikeJsonBody("text/plain", "{\"a\":1}"));
        assertTrue(Job51SearchApi.looksLikeJsonBody("application/json;charset=utf-8", "x"));
        assertFalse(Job51SearchApi.looksLikeJsonBody(null, "<html>"));
        assertFalse(Job51SearchApi.looksLikeJsonBody("text/html", "<html>"));
    }
}
