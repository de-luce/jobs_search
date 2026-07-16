package com.getjobs.worker.liepin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiepinSearchApiTest {

    @Test
    void jobSearchUrl_shouldMatch() {
        String url = "https://api-c.liepin.com/api/com.liepin.searchfront4c.pc-search-job?currentPage=1";
        assertTrue(LiepinSearchApi.isJobSearchResponse(url, 200));
        assertTrue(LiepinSearchApi.isJobSearchUrl(url));
    }

    @Test
    void condInitUrl_mustNotMatch_evenThoughItContainsJobSearchSubstring() {
        String url = "https://api-c.liepin.com/api/com.liepin.searchfront4c.pc-search-job-cond-init";
        assertFalse(LiepinSearchApi.isJobSearchResponse(url, 200));
        assertFalse(LiepinSearchApi.isJobSearchUrl(url));
    }

    @Test
    void non200_shouldNotMatch() {
        String url = "https://api-c.liepin.com/api/com.liepin.searchfront4c.pc-search-job";
        assertFalse(LiepinSearchApi.isJobSearchResponse(url, 500));
    }

    @Test
    void nullUrl_shouldNotMatch() {
        assertFalse(LiepinSearchApi.isJobSearchResponse(null, 200));
    }
}
