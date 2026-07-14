package com.getjobs.application.utils;

/**
 * 全平台统一的投递状态文案（与 boss_data / zhilian_data 的 delivery_status 一致）。
 */
public final class DeliveryStatuses {

    public static final String PENDING = "未投递";
    public static final String DELIVERED = "已投递";
    public static final String FILTERED = "已过滤";
    public static final String FAILED = "投递失败";

    private DeliveryStatuses() {}

    public static String normalize(String status) {
        if (status == null || status.isBlank()) return PENDING;
        String t = status.trim();
        if (DELIVERED.equals(t) || FILTERED.equals(t) || FAILED.equals(t) || PENDING.equals(t)) {
            return t;
        }
        return PENDING;
    }

    /** 兼容旧整型 delivered：0/1/2 */
    public static String fromLegacyCode(Integer code) {
        if (code == null || code == 0) return PENDING;
        if (code == 1) return DELIVERED;
        if (code == 2) return FILTERED;
        return PENDING;
    }
}
