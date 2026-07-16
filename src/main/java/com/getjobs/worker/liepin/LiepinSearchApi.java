package com.getjobs.worker.liepin;

/**
 * 猎聘 PC 搜索岗位接口识别。
 * <p>
 * 注意：{@code pc-search-job-cond-init} 的 URL 也包含 {@code pc-search-job} 子串，
 * 必须显式排除，否则翻页 waitForResponse 会误判为岗位列表已返回。
 */
public final class LiepinSearchApi {

    private static final String JOB_SEARCH = "com.liepin.searchfront4c.pc-search-job";
    private static final String JOB_SEARCH_COND_INIT = "com.liepin.searchfront4c.pc-search-job-cond-init";

    private LiepinSearchApi() {}

    /** 是否为岗位列表搜索接口（不含条件初始化接口） */
    public static boolean isJobSearchResponse(String url, int status) {
        if (url == null || status != 200) {
            return false;
        }
        return url.contains(JOB_SEARCH) && !url.contains(JOB_SEARCH_COND_INIT);
    }

    public static boolean isJobSearchUrl(String url) {
        return isJobSearchResponse(url, 200);
    }
}
