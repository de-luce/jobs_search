package com.getjobs.worker.job51;

/**
 * 51job PC 搜索接口识别。
 */
public final class Job51SearchApi {

    private static final String SEARCH_PC = "/api/job/search-pc";

    private Job51SearchApi() {}

    /** 是否为岗位搜索接口（GET/POST 均可） */
    public static boolean isSearchPcRequest(String url, String method) {
        if (url == null || !url.contains(SEARCH_PC)) {
            return false;
        }
        if (method == null || method.isBlank()) {
            return true;
        }
        String m = method.trim();
        return "GET".equalsIgnoreCase(m) || "POST".equalsIgnoreCase(m);
    }

    /** 响应体是否可按 JSON 解析（不依赖 Content-Type，避免漏落库） */
    public static boolean looksLikeJsonBody(String contentType, String body) {
        if (contentType != null && contentType.toLowerCase().contains("json")) {
            return true;
        }
        if (body == null) {
            return false;
        }
        String t = body.trim();
        return !t.isEmpty() && (t.charAt(0) == '{' || t.charAt(0) == '[');
    }
}
