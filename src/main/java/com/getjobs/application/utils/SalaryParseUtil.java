package com.getjobs.application.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一薪资解析：结果一律换算为「月薪 K」（1K = 1000 元/月）。
 * <p>
 * 支持示例：
 * <ul>
 *   <li>Boss：20-40K、35-65K·16薪</li>
 *   <li>51job / 智联：1.5-2万、1.5-2万·13薪、25-41万/年、9000-16000元</li>
 *   <li>猎聘年薪档：10-15万/年</li>
 *   <li>千元：6-8千、0.8-1万</li>
 * </ul>
 */
public final class SalaryParseUtil {

    private static final Pattern MONTHS = Pattern.compile("[·.\\-]?([0-9]{1,2})薪");
    private static final Pattern RANGE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*[-~～—]+\\s*(\\d+(?:\\.\\d+)?)");
    private static final Pattern SINGLE = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    private SalaryParseUtil() {}

    public static final class SalaryInfo {
        public Double minK;
        public Double maxK;
        public Integer months = 12;
        public Double medianK;
        public Long annualTotal;
    }

    public static SalaryInfo parse(String raw) {
        if (raw == null) return null;
        String original = raw.trim();
        if (original.isEmpty() || original.contains("面议")) return null;

        String s = original.replace(" ", "").toLowerCase();

        Integer months = 12;
        Matcher mMonths = MONTHS.matcher(s);
        if (mMonths.find()) {
            try {
                months = Integer.parseInt(mMonths.group(1));
            } catch (Exception ignored) {}
            s = s.substring(0, mMonths.start());
        }

        Double a = null;
        Double b = null;
        Matcher mRange = RANGE.matcher(s);
        if (mRange.find()) {
            a = Double.valueOf(mRange.group(1));
            b = Double.valueOf(mRange.group(2));
        } else {
            Matcher mSingle = SINGLE.matcher(s);
            if (mSingle.find()) {
                a = Double.valueOf(mSingle.group(1));
                b = a;
            }
        }
        if (a == null || b == null) return null;

        double min = Math.min(a, b);
        double max = Math.max(a, b);
        double factorToK = resolveFactorToMonthlyK(s, original);

        SalaryInfo info = new SalaryInfo();
        info.minK = min * factorToK;
        info.maxK = max * factorToK;
        info.months = months != null ? months : 12;
        info.medianK = (info.minK + info.maxK) / 2.0;
        info.annualTotal = Math.round(info.medianK * 1000 * info.months);
        return info;
    }

    /**
     * 数值单位 → 月薪 K 的乘数。
     */
    static double resolveFactorToMonthlyK(String normalizedLower, String original) {
        String s = normalizedLower == null ? "" : normalizedLower;
        // K / k
        if (s.contains("k")) {
            return 1.0;
        }
        // 日薪（元/天）粗算：22 个工作日
        if (s.contains("元/天") || s.contains("/天")) {
            return 22.0 / 1000.0;
        }
        // 年薪（万/年、年薪xx万）：先转月 → /12，万→K ×10
        boolean annual = s.contains("/年") || s.contains("年薪")
                || (s.contains("万") && s.contains("年") && !s.contains("/月"));
        if (s.contains("万")) {
            return annual ? (10.0 / 12.0) : 10.0;
        }
        // 千元（含“千”或以裸小数配合搜索档位）
        if (s.contains("千")) {
            return 1.0;
        }
        // 纯「元」区间：如 9000-16000元
        if (s.contains("元")) {
            return annual ? (1.0 / 1000.0 / 12.0) : (1.0 / 1000.0);
        }
        // 裸数字过大（>100）视为元
        Matcher m = RANGE.matcher(s);
        if (m.find()) {
            double sample = Math.max(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)));
            if (sample >= 1000) {
                return 1.0 / 1000.0;
            }
            if (sample >= 100) {
                // 100-999 可能是元或异常；保守按元
                return 1.0 / 1000.0;
            }
        }
        // 默认：小数档多为「万」展示习惯（1.5-2），大于等于 1 且无单位时：
        // 若原串含典型岗位薪资且无 K，仍优先按万（51job/智联常见）
        if (original != null && !original.toLowerCase().contains("k") && !original.contains("千")) {
            Matcher single = SINGLE.matcher(s);
            if (single.find()) {
                double v = Double.parseDouble(single.group(1));
                if (v > 0 && v < 100) {
                    return 10.0; // 当作万
                }
            }
        }
        return 1.0;
    }
}
