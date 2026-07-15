package com.getjobs.worker.boss;

import com.getjobs.application.entity.AiEntity;
import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BlacklistService;
import com.getjobs.application.service.BossService;
import com.getjobs.application.utils.DeliveryStatuses;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.JobUtils;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import static com.getjobs.worker.boss.Locators.*;


/**
 * @author loks666
 * 项目链接: <a href=
 * "https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 * Boss直聘自动投递
 */
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class Boss {

    @Setter
    private Page page;
    @Setter
    private BossConfig config;
    private final BossService bossService;
    private final AiService aiService;
    private final BlacklistService blacklistService;
    // 记录 encryptId -> encryptUserId 的映射，用于后续更新投递状态
    private final ConcurrentMap<String, String> encryptIdToUserId = new ConcurrentHashMap<>();
    @Setter
    private ProgressCallback progressCallback;
    @Setter
    private Supplier<Boolean> shouldStopCallback;

    private final List<Job> resultList = new ArrayList<>();

    /**
     * 进度回调接口
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void accept(String message, Integer current, Integer total);
    }

    // 通过 Lombok @RequiredArgsConstructor 使用构造器注入 bossService 与 aiService

    public void prepare() {
        // 调整 boss_data 表结构：将 encrypt_id、encrypt_user_id 前置
        try { bossService.ensureBossDataColumnOrder(); } catch (Throwable ignore) {}
        // 不在页面初始化阶段入库，仅用于后续点击卡片时按需入库
    }

    /**
     * 执行投递
     */
    public int execute() {
        for (String cityCode : config.getCityCode()) {
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                progressCallback.accept("用户取消投递", 0, 0);
                break;
            }
            postJobByCity(cityCode);
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                progressCallback.accept("用户取消投递", 0, 0);
                break;
            }
        }
        return resultList.size();
    }

    /**
     * 获取结果列表
     */
    public List<Job> getResultList() {
        return new ArrayList<>(resultList);
    }

    private void postJobByCity(String cityCode) {
        String searchUrl = getSearchUrl(cityCode);
        for (String keyword : config.getKeywords()) {
            // 检查是否需要停止
            if (shouldStopCallback.get()) {
                progressCallback.accept("用户取消投递", 0, 0);
                return;
            }

            int postCount = 0;
            // 使用 URLEncoder 对关键词进行编码
            String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8);

            String url = searchUrl + (searchUrl.contains("?") ? "&" : "?") + "query=" + encodedKeyword;
            log.info("Boss 搜索 URL: {}", url);
            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(30_000));
            if (!waitForJobsListOrFailSecurity(keyword)) {
                return;
            }

            // 1. 基于 footer 出现滚动到底，确保加载全部岗位
            int lastCount = -1;
            int stableTries = 0;
            for (int i = 0; i < 5000; i++) { // 最多尝试约120次，避免死循环
                // 停止检查：滚动加载过程中也要及时响应
                if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                    progressCallback.accept("用户取消投递", 0, 0);
                    return;
                }
                Locator footer = page.locator("div#footer, #footer");
                if (footer.count() > 0 && footer.first().isVisible()) {
                    break; // 到达页面底部
                }
                // 按视口高度的90%渐进滚动，触发懒加载
                page.evaluate("() => window.scrollBy(0, Math.floor(window.innerHeight * 1.5))");

                // 获取卡片数量变化，判断是否需要强制触底
                Locator cardsProbe = page.locator("//ul[contains(@class, 'rec-job-list')]//li[contains(@class, 'job-card-box')]");
                int currentCount = cardsProbe.count();
                if (currentCount == lastCount) {
                    stableTries++;
                } else {
                    stableTries = 0;
                }
                lastCount = currentCount;

                if (stableTries >= 3) { // 连续多次无新增，则强制触底一次
                    page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
                    // 触底不再等待，继续检测 footer 出现
                }
            }
            // 统计最终岗位数量
            Locator cardsFinal = page.locator("//ul[contains(@class, 'rec-job-list')]//li[contains(@class, 'job-card-box')]");
            int loadedCount = cardsFinal.count();
            log.info("【{}】岗位已全部加载，总数:{}", keyword, loadedCount);
            progressCallback.accept("岗位加载完成：" + keyword, 0, loadedCount);

            // 2. 回到页面顶部
            page.evaluate("window.scrollTo(0, 0);");
            PlaywrightUtil.sleep(1);

            // 3. 逐个遍历所有岗位
            Locator cards = page.locator("//ul[contains(@class, 'rec-job-list')]//li[contains(@class, 'job-card-box')]");
            int count = cards.count();
            for (int i = 0; i < count; i++) {
                // 检查是否需要停止
                if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                    progressCallback.accept("用户取消投递", i, count);
                    return;
                }

                // 重新获取卡片，避免元素过期
                cards = page.locator("//ul[contains(@class, 'rec-job-list')]//li[contains(@class, 'job-card-box')]");
                // 在点击卡片时同步等待岗位详情接口返回，随后解析并入库
                Response detailResp = null;
                try {
                    if (i == 0 && count > 1) {
                        // 第一个卡片默认展开不会触发请求：先切到第二个，再切回第一个，并在返回第一个时监听响应
                        final Locator secondCard = cards.nth(1);
                        secondCard.click();
                        PlaywrightUtil.sleep(1);
                        final Locator firstCard = cards.nth(0);
                        detailResp = page.waitForResponse(r -> {
                            try {
                                return r.url() != null && r.url().contains("/wapi/zpgeek/job/detail.json")
                                        && "GET".equalsIgnoreCase(r.request().method());
                            } catch (Throwable ignore) { return false; }
                        }, firstCard::click);
                    } else {
                        final Locator cardToClick = cards.nth(i);
                        detailResp = page.waitForResponse(r -> {
                            try {
                                return r.url() != null && r.url().contains("/wapi/zpgeek/job/detail.json")
                                        && "GET".equalsIgnoreCase(r.request().method());
                            } catch (Throwable ignore) { return false; }
                        }, cardToClick::click);
                    }
                } catch (Throwable ignore) {
                }
                PlaywrightUtil.sleep(1);

                // 统一从请求返回的 JSON 中获取数据并做过滤
                String jobName = null;
                String jobSalary = null;
                java.util.List<String> tags = new java.util.ArrayList<>();
                String jobDesc = null;
                String bossName = null;
                String bossActive = null;
                String bossCompany = null;
                java.util.List<String> companyCandidates = new java.util.ArrayList<>();

                if (detailResp == null) {
                    log.info("岗位详情未获取到，跳过投递（无法校验黑名单）| 索引：{}", i);
                    continue;
                }
                try {
                    String body = detailResp.text();
                    // 保存原始 JSON 便于调试
                    appendRawJson(body);
                    // 解析并入库（仅在点击卡片触发时执行）
                    processJobDetailJsonAndInsert(body);

                    // 从 JSON 构建用于投递与过滤的字段
                    org.json.JSONObject root = new org.json.JSONObject(body);
                    org.json.JSONObject zpData = root.optJSONObject("zpData");
                    org.json.JSONObject jobInfo = zpData != null ? zpData.optJSONObject("jobInfo") : null;
                    org.json.JSONObject brand = zpData != null ? zpData.optJSONObject("brandComInfo") : null;
                    org.json.JSONObject boss = zpData != null ? zpData.optJSONObject("bossInfo") : null;

                    if (jobInfo != null) {
                        jobName = jobInfo.optString("jobName", "");
                        jobSalary = jobInfo.optString("salaryDesc", "");
                        String city = jobInfo.optString("locationName", "");
                        String exp = jobInfo.optString("experienceName", "");
                        String deg = jobInfo.optString("degreeName", "");
                        if (!city.isEmpty()) tags.add(city);
                        if (!exp.isEmpty()) tags.add(exp);
                        if (!deg.isEmpty()) tags.add(deg);
                        jobDesc = jobInfo.optString("postDescription", "");
                    }

                    if (boss != null) {
                        bossName = boss.optString("name", "");
                        bossActive = boss.optString("activeTimeDesc", "");
                    }

                    companyCandidates = collectBossCompanyNames(brand);
                    bossCompany = companyCandidates.isEmpty() ? null : companyCandidates.get(0);
                } catch (Throwable e) {
                    log.info("点击卡片后解析岗位详情失败，跳过投递（无法校验黑名单）| 原因：{}", e.getMessage());
                    continue;
                }

                if (companyCandidates.isEmpty()) {
                    log.info("公司名为空，跳过投递（无法校验黑名单）| 岗位：{}", jobName != null ? jobName : "");
                    continue;
                }

                // HR活跃状态过滤：当开启过滤开关且活跃描述包含“年”时，视为不活跃
                boolean hrInactiveByYear = bossActive != null && bossActive.contains("年");
                if (Boolean.TRUE.equals(config.getFilterDeadHR()) && hrInactiveByYear) {
                    log.info("被过滤：HR活跃状态包含‘年’ | 公司：{} | 岗位：{} | 活跃：{}", bossCompany != null ? bossCompany : "", jobName != null ? jobName : "", bossActive);
                    continue;
                }

                // 全局公司黑名单：品牌名/全称等任一命中则跳过投递
                String matchedBlacklist = blacklistService.findMatchedCompanyAny(companyCandidates);
                if (matchedBlacklist != null) {
                    log.info("被过滤：公司名命中全局黑名单「{}」 | 公司：{} | 岗位：{}", matchedBlacklist, bossCompany != null ? bossCompany : "", jobName != null ? jobName : "");
                    continue;
                }

                // 创建Job对象（全部基于 JSON 字段）
                Job job = new Job();
                job.setJobName(jobName != null ? jobName : "");
                job.setSalary(jobSalary != null ? jobSalary : "");
                job.setJobArea(String.join(", ", tags));
                job.setCompanyName(bossCompany != null ? bossCompany : "");
                job.setRecruiter(bossName != null ? bossName : "");
                job.setJobInfo(jobDesc != null ? jobDesc : "");

                // 输出
                progressCallback.accept("正在投递：" + jobName, i + 1, count);
                resumeSubmission(keyword, job);
                postCount++;

                // 为避免点击下面的卡片触发页面刷新：在点击5个卡片之后，每次点击后适度下滑
                try {
                    if (i >= 5) {
                        page.evaluate("window.scrollBy(0, 140);");
                        PlaywrightUtil.sleep(1);
                    }
                } catch (Throwable ignore) {}
            }
            log.info("【{}】岗位已投递完毕！已投递岗位数量:{}", keyword, postCount);
        }
    }

    /**
     * 解析岗位详情 JSON 并进行入库（只在点击卡片时调用）。
     */
    private void processJobDetailJsonAndInsert(String body) {
        if (body == null || body.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(body);
            JSONObject zpData = root.optJSONObject("zpData");
            if (zpData == null) return;

            JSONObject jobInfo = zpData.optJSONObject("jobInfo");
            JSONObject brand = zpData.optJSONObject("brandComInfo");
            JSONObject bossInfo = zpData.optJSONObject("bossInfo");
            if (jobInfo == null) return;

            String encryptId = jobInfo.optString("encryptId", null);
            String encryptUserId = jobInfo.optString("encryptUserId", null);
            if (encryptUserId == null && bossInfo != null) {
                // 兼容部分页面字段落在 bossInfo 内
                encryptUserId = bossInfo.optString("encryptUserId", null);
                if (encryptUserId == null) {
                    // 进一步兼容可能的字段命名
                    encryptUserId = bossInfo.optString("encryptBossId", null);
                }
            }
            if (encryptId != null && encryptUserId != null) {
                encryptIdToUserId.put(encryptId, encryptUserId);
            }

            com.getjobs.application.entity.BossJobDataEntity entity = new com.getjobs.application.entity.BossJobDataEntity();
            entity.setJobName(jobInfo.optString("jobName", null));
            entity.setSalary(jobInfo.optString("salaryDesc", null));
            entity.setLocation(jobInfo.optString("locationName", null));
            entity.setExperience(jobInfo.optString("experienceName", null));
            entity.setDegree(jobInfo.optString("degreeName", null));
            entity.setJobDescription(jobInfo.optString("postDescription", null));
            entity.setRecruitmentStatus(jobInfo.optString("jobStatusDesc", null));
            entity.setCompanyAddress(jobInfo.optString("address", null));
            entity.setEncryptId(encryptId);
            entity.setEncryptUserId(encryptUserId);

            java.util.List<String> companyCandidates = collectBossCompanyNames(brand);
            entity.setCompanyName(companyCandidates.isEmpty() ? null : companyCandidates.get(0));
            entity.setIndustry(brand != null ? brand.optString("industryName", null) : null);
            entity.setIntroduce(brand != null ? brand.optString("introduce", null) : null);
            entity.setFinancingStage(brand != null ? brand.optString("stageName", null) : null);
            entity.setCompanyScale(brand != null ? brand.optString("scaleName", null) : null);

            entity.setHrName(bossInfo != null ? bossInfo.optString("name", null) : null);
            entity.setHrPosition(bossInfo != null ? bossInfo.optString("title", null) : null);
            entity.setHrActiveStatus(bossInfo != null ? bossInfo.optString("activeTimeDesc", null) : null);

            if (encryptId != null && !encryptId.isEmpty()) {
                entity.setJobUrl("https://www.zhipin.com/job_detail/" + encryptId + ".html");
            }

            boolean filtered = false;

            // HR活跃状态过滤：开启过滤且活跃描述包含“年”，则标记为已过滤，但仍入库
            if (!filtered && Boolean.TRUE.equals(config.getFilterDeadHR())) {
                String hrActive = entity.getHrActiveStatus();
                if (hrActive != null && hrActive.contains("年")) {
                    filtered = true;
                }
            }

            // 全局公司黑名单：品牌名/全称等任一命中则标记为已过滤，仍入库
            if (!filtered && blacklistService.findMatchedCompanyAny(companyCandidates) != null) {
                filtered = true;
            }

            entity.setDeliveryStatus(filtered ? DeliveryStatuses.FILTERED : DeliveryStatuses.PENDING);

            // 入库（若不存在），优先以 encrypt_id + encrypt_user_id 去重；若 userId 缺失，则以 encrypt_id 去重
            if (encryptId != null) {
                try {
                    boolean exists = false;
                    if (encryptUserId != null) {
        exists = bossService.existsBossJob(encryptId, encryptUserId);
                    } else {
        exists = bossService.existsBossJobByEncryptId(encryptId);
                    }
                    if (!exists) {
        bossService.insertBossJob(entity);
                        log.debug("岗位入库：{} | 公司：{} | HR：{} | 状态：{}", entity.getJobName(), entity.getCompanyName(), entity.getHrName(), entity.getDeliveryStatus());
                    }
                } catch (Exception e) {
                    log.warn("岗位入库失败：{}", e.getMessage());
                }
            }
        } catch (Throwable e) {
            log.debug("解析岗位详情 JSON 失败：{}", e.getMessage());
        }
    }

    /** 从 brandComInfo 收集可用于黑名单匹配的公司名（品牌名优先，兼容可能存在的全称字段） */
    private static java.util.List<String> collectBossCompanyNames(JSONObject brand) {
        java.util.List<String> names = new java.util.ArrayList<>();
        if (brand == null) {
            return names;
        }
        String[] keys = {"brandName", "companyName", "comName", "customerName", "brandFullName", "name"};
        for (String key : keys) {
            String v = brand.optString(key, null);
            if (v == null || v.isBlank() || BlacklistService.isPlaceholderCompany(v)) {
                continue;
            }
            String trimmed = v.trim();
            if (!names.contains(trimmed)) {
                names.add(trimmed);
            }
        }
        return names;
    }

    public String decodeSalary(String text) {
        Map<Character, Character> fontMap = new HashMap<>();
        fontMap.put('\uE8F0', '0');
        fontMap.put('\uE8F1', '1');
        fontMap.put('\uE8F2', '2');
        fontMap.put('\uE8F3', '3');
        fontMap.put('\uE8F4', '4');
        fontMap.put('\uE8F5', '5');
        fontMap.put('\uE8F6', '6');
        fontMap.put('\uE8F7', '7');
        fontMap.put('\uE8F8', '8');
        fontMap.put('\uE8F9', '9');
        StringBuilder result = new StringBuilder();
        for (char c : text.toCharArray()) {
            result.append(fontMap.getOrDefault(c, c));
        }
        return result.toString();
    }

    // 安全获取单个文本内容
    public String safeText(Locator root, String selector) {
        Locator node = root.locator(selector);
        try {
            if (node.count() > 0 && node.innerText() != null) {
                return node.innerText().trim();
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }

    // 安全获取多个文本内容
    public List<String> safeAllText(Locator root, String selector) {
        try {
            return root.locator(selector).allInnerTexts();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // Boss姓名+活跃状态拆分
    public String[] splitBossName(String raw) {
        String[] bossParts = raw.trim().split("\\s+");
        String bossName = bossParts[0];
        String bossActive = bossParts.length > 1 ? String.join(" ", Arrays.copyOfRange(bossParts, 1, bossParts.length)) : "";
        return new String[]{bossName, bossActive};
    }

    // Boss公司+职位拆分
    public String[] splitBossTitle(String raw) {
        String[] parts = raw.trim().split(" · ");
        String company = parts[0];
        String job = parts.length > 1 ? parts[1] : "";
        return new String[]{company, job};
    }

    public static String buildSearchUrl(BossConfig config, String cityCode) {
        String baseUrl = "https://www.zhipin.com/web/geek/jobs";
        if (config == null) {
            return baseUrl;
        }
        List<String> params = new ArrayList<>();
        addParam(params, JobUtils.appendParam("city", cityCode));
        addParam(params, JobUtils.appendParam("jobType", config.getJobType()));
        addParam(params, JobUtils.appendListParam("salary", config.getSalary()));
        addParam(params, JobUtils.appendListParam("experience", config.getExperience()));
        addParam(params, JobUtils.appendListParam("degree", config.getDegree()));
        addParam(params, JobUtils.appendListParam("scale", config.getScale()));
        addParam(params, JobUtils.appendListParam("industry", config.getIndustry()));
        addParam(params, JobUtils.appendListParam("stage", config.getStage()));
        if (params.isEmpty()) {
            return baseUrl;
        }
        return baseUrl + "?" + String.join("&", params);
    }

    private static void addParam(List<String> params, String param) {
        if (param == null || param.isEmpty()) {
            return;
        }
        params.add(param.startsWith("&") ? param.substring(1) : param);
    }

    private String getSearchUrl(String cityCode) {
        return buildSearchUrl(config, cityCode);
    }

    /**
     * 等待职位列表出现；若卡在 Boss 安全校验页（security.html / _security_check 循环）则中止并提示。
     * 注意：URL 里的 ts/seed 是风控参数，不是本系统拼接的时间戳。
     */
    private boolean waitForJobsListOrFailSecurity(String keyword) {
        final long deadline = System.currentTimeMillis() + 60_000L;
        int lastSecurityHop = -1;
        int sameHopTicks = 0;

        while (System.currentTimeMillis() < deadline) {
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                progressCallback.accept("用户取消投递", 0, 0);
                return false;
            }

            String currentUrl = null;
            try {
                currentUrl = page.url();
            } catch (Exception ignored) {
            }

            if (isBossSecurityPage(currentUrl)) {
                int hop = extractSecurityCheckHop(currentUrl);
                if (hop > 0) {
                    if (hop == lastSecurityHop) {
                        sameHopTicks++;
                    } else {
                        lastSecurityHop = hop;
                        sameHopTicks = 0;
                        log.warn("Boss 安全校验进行中（第 {} 跳），等待通过… | keyword={}", hop, keyword);
                        progressCallback.accept("Boss 安全校验中（第 " + hop + " 跳），请稍候…", 0, 0);
                    }
                }
                // 深层嵌套或长时间停在同一跳，判定校验失败（源站反爬，非本系统拼 ts）
                if (hop >= 5 || sameHopTicks >= 15) {
                    String msg = "Boss 安全校验未通过（疑似触发反爬）。请重启浏览器后重新登录，降低操作频率后再试。";
                    log.error("{} | url={}", msg, abbreviateUrl(currentUrl));
                    progressCallback.accept(msg, 0, 0);
                    return false;
                }
                PlaywrightUtil.sleep(1);
                continue;
            }

            try {
                page.waitForSelector("//ul[contains(@class, 'rec-job-list')]",
                        new Page.WaitForSelectorOptions().setTimeout(3_000));
                return true;
            } catch (Exception e) {
                PlaywrightUtil.sleep(1);
            }
        }

        String msg = "等待 Boss 职位列表超时：页面可能仍在安全校验或未登录。";
        log.error("{} | keyword={} | url={}", msg, keyword, abbreviateUrl(safePageUrl()));
        progressCallback.accept(msg, 0, 0);
        return false;
    }

    private String safePageUrl() {
        try {
            return page.url();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBossSecurityPage(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        return url.contains("/web/passport/zp/security")
                || url.contains("/web/common/security-check")
                || url.contains("security-check")
                || url.contains("_security_check");
    }

    /**
     * 从 callbackUrl 嵌套参数中解析 _security_check=N，数值越大表示重定向循环越深。
     */
    private static int extractSecurityCheckHop(String url) {
        if (url == null) {
            return 0;
        }
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("_security_check[=%](\\d+)")
                    .matcher(url);
            int max = 0;
            while (m.find()) {
                max = Math.max(max, Integer.parseInt(m.group(1)));
            }
            return max;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String abbreviateUrl(String url) {
        if (url == null) {
            return null;
        }
        return url.length() <= 180 ? url : url.substring(0, 180) + "...(len=" + url.length() + ")";
    }

    /**
     * 备注：目前Boss无法通过新标签页打开立即沟通按钮，所以只能点击更多详情，然后从更多详情里打开聊天按钮
     */
    @SneakyThrows
    private void resumeSubmission(String keyword, Job job) {
        // 若收到停止指令，直接短路返回
        if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
            log.info("停止指令已触发，跳过投递 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
            return;
        }
        // 调试模式：仅遍历不投递
        if (Boolean.TRUE.equals(config.getDebugger())) {
            log.info("调试模式：仅遍历岗位，不投递 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
            return;
        }

        // 1. 查找"查看更多信息"按钮（必须存在且新开页）
        Locator moreInfoBtn = page.locator("a.more-job-btn");
        if (moreInfoBtn.count() == 0) {
            log.warn("未找到\"查看更多信息\"按钮，跳过...");
            return;
        }
        // 强制用js新开tab
        String href = moreInfoBtn.first().getAttribute("href");
        if (href == null || !href.startsWith("/job_detail/")) {
            log.warn("未获取到岗位详情链接，跳过...");
            return;
        }
        String detailUrl = "https://www.zhipin.com" + href;
        // 2. 在新窗口打开详情页
        Page detailPage = page.context().newPage();
        detailPage.navigate(detailUrl);
        PlaywrightUtil.sleep(1);

        // 3. 查找沟通按钮：「立即沟通」可新投；「继续沟通/继续聊」视为已投递
        Locator chatBtn = detailPage.locator("a.btn-startchat, a.op-btn-chat");
        String chatBtnText = "";
        boolean foundChatBtn = false;
        for (int i = 0; i < 5; i++) {
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                log.info("停止指令已触发，结束查找聊天按钮 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
                try { detailPage.close(); } catch (Exception ignore) {}
                return;
            }
            if (chatBtn.count() > 0) {
                try {
                    chatBtnText = chatBtn.first().textContent();
                } catch (Exception ignore) {
                    chatBtnText = "";
                }
                if (chatBtnText != null && !chatBtnText.isBlank()) {
                    foundChatBtn = true;
                    break;
                }
            }
            PlaywrightUtil.sleep(1);
        }
        if (!foundChatBtn) {
            log.warn("未找到沟通按钮，跳过岗位: {}", job.getJobName());
            try {
                detailPage.close();
            } catch (Exception ignore) {
            }
            markBossDeliveryStatus(detailUrl, DeliveryStatuses.FAILED);
            return;
        }

        if (DeliveryStatuses.isAlreadyChattedButton(chatBtnText)) {
            log.info("已沟通过（按钮「{}」），标记已投递并跳过 | 公司：{} | 岗位：{}",
                    chatBtnText.trim(), job.getCompanyName(), job.getJobName());
            try {
                detailPage.close();
            } catch (Exception ignore) {
            }
            markBossDeliveryStatus(detailUrl, DeliveryStatuses.DELIVERED);
            return;
        }

        if (!DeliveryStatuses.isFreshChatButton(chatBtnText)) {
            log.warn("沟通按钮文案未识别「{}」，跳过岗位: {}", chatBtnText, job.getJobName());
            try {
                detailPage.close();
            } catch (Exception ignore) {
            }
            markBossDeliveryStatus(detailUrl, DeliveryStatuses.FAILED);
            return;
        }

        chatBtn.first().click();
        PlaywrightUtil.sleep(1);

        // 4. 等待聊天窗口出现
        Locator closeBtn = detailPage.locator("i.icon-close");
        Locator chatDialog = detailPage.locator("div.dialog-container, div.dialog-con");
        boolean chatReady = false;
        for (int i = 0; i < 10; i++) {
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                log.info("停止指令已触发，结束等待聊天窗口 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
                try { detailPage.close(); } catch (Exception ignore) {}
                return;
            }
            if ((closeBtn.count() > 0 && closeBtn.first().isVisible())
                    || (chatDialog.count() > 0 && chatDialog.first().isVisible())) {
                chatReady = true;
                break;
            }
            PlaywrightUtil.sleep(1);
        }
        if (!chatReady) {
            log.warn("聊天窗口未出现，跳过: {}", job.getJobName());
            try {
                detailPage.close();
            } catch (Exception ignore) {
            }
            markBossDeliveryStatus(detailUrl, DeliveryStatuses.FAILED);
            return;
        }

        // 5. 投递招呼语：默认与猎聘一致由官网自动发送；开启 AI 时尝试发送个性化内容
        boolean sendSuccess;
        String message;
        String aiMessage = tryGenerateAiGreeting(detailPage, keyword, job);
        if (isValidString(aiMessage)) {
            sendSuccess = sendChatMessage(detailPage, aiMessage);
            message = aiMessage;
            closeChatDialog(detailPage);
            if (!sendSuccess) {
                log.warn("AI 招呼语发送失败，岗位：{}", job.getJobName());
            }
        } else {
            // Boss 与猎聘一致：点击沟通后官网自动发送默认招呼语，关闭聊天窗口即可
            PlaywrightUtil.sleep(1);
            closeChatDialog(detailPage);
            sendSuccess = true;
            message = "Boss官网自动发送";
        }

        // 6. 发送图片简历（可选）
        boolean imgResume = false;
        if (Boolean.TRUE.equals(config.getSendImgResume())) {
            imgResume = sendImageResume(detailPage);
        }

        log.info("投递完成 | 公司：{} | 岗位：{} | 薪资：{} | 招呼语：{} | 图片简历：{}", job.getCompanyName(), job.getJobName(), job.getSalary(), message, imgResume ? "已发送" : "未发送");

        // 7. 关闭新打开的详情页
        try {
            detailPage.close();
        } catch (Exception ignore) {
        }
        PlaywrightUtil.sleep(1);

        // 8. 更新数据库投递状态 & 成功投递加入结果
        if (sendSuccess) {
            markBossDeliveryStatus(detailUrl, DeliveryStatuses.DELIVERED);
            resultList.add(job);
            log.info("投递成功 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
        } else {
            markBossDeliveryStatus(detailUrl, DeliveryStatuses.FAILED);
            log.warn("投递失败 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
        }
    }

    private void markBossDeliveryStatus(String detailUrl, String status) {
        String encryptId = extractEncryptId(detailUrl);
        String encryptUserId = encryptId != null ? encryptIdToUserId.get(encryptId) : null;
        if (encryptId == null) {
            log.debug("未能找到 encryptId 用于更新投递状态，detailUrl: {}", detailUrl);
            return;
        }
        try {
            bossService.updateDeliveryStatus(encryptId, encryptUserId, status);
        } catch (Exception e) {
            log.warn("更新投递状态为「{}」失败：{}", status, e.getMessage());
        }
    }

    

    /**
     * 注册页面响应监听：拦截 /wapi/zpgeek/job/detail.json 请求并解析写库
     */
    private void attachJobDetailResponseListener() {
        if (page == null) return;
        page.onResponse(resp -> {
            try {
                String url = resp.url();
                if (url == null) return;
                // 仅处理 Boss 岗位详情接口（GET）
                if (url.contains("/wapi/zpgeek/job/detail.json") &&
                        "GET".equalsIgnoreCase(resp.request().method())) {
                    String body = null;
                    try {
                        body = resp.text();
                    } catch (Throwable ignore) {
                        // 某些情况下可能拿不到文本，忽略
                    }
                    if (body == null || body.isEmpty()) return;

                    // 保存原始 JSON 到 target/job.txt
                    appendRawJson(body);

                    // 仅记录映射与原始 JSON；入库逻辑已移动到点击卡片时
                    JSONObject root = new JSONObject(body);
                    JSONObject zpData = root.optJSONObject("zpData");
                    if (zpData == null) return;
                    JSONObject jobInfo = zpData.optJSONObject("jobInfo");
                    if (jobInfo == null) return;
                    String encryptId = jobInfo.optString("encryptId", null);
                    String encryptUserId = jobInfo.optString("encryptUserId", null);
                    if (encryptId != null && encryptUserId != null) {
                        encryptIdToUserId.put(encryptId, encryptUserId);
                    }
                }
            } catch (Throwable e) {
                log.debug("监听岗位详情响应处理异常：{}", e.getMessage());
            }
        });
    }


    /**
     * 追加保存原始 JSON 到 target/job.txt
     */
    private void appendRawJson(String body) {
        try {
            java.io.File dir = new java.io.File("target");
            if (!dir.exists()) dir.mkdirs();
            java.io.File file = new java.io.File(dir, "job.txt");
            try (java.io.FileWriter fw = new java.io.FileWriter(file, true)) {
                fw.write(body);
                fw.write(System.lineSeparator());
                fw.write("\n");
            }
        } catch (Exception e) {
            log.debug("写入 target/job.txt 失败：{}", e.getMessage());
        }
    }

    /**
     * 从详情页 URL 中提取 encrypt_id
     */
    private String extractEncryptId(String detailUrl) {
        try {
            if (detailUrl == null) return null;
            String key = "/job_detail/";
            int idx = detailUrl.indexOf(key);
            if (idx < 0) return null;
            int start = idx + key.length();
            int end = detailUrl.indexOf(".html", start);
            if (end < 0) end = detailUrl.length();
            return detailUrl.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isValidString(String str) {
        return str != null && !str.isEmpty();
    }

    private boolean sendImageResume(Page page) {
        try {
            // 0) 资源存在性校验，避免后续无效操作
            URL resourceUrlCheck = Boss.class.getResource("/resume.jpg");
            if (resourceUrlCheck == null) {
                log.error("资源文件 resume.jpg 不存在，跳过发送图片简历");
                return false;
            }

            // 进入聊天页
            if (!page.url().contains("/web/geek/chat")) {
                Locator chatBtn = page.locator("a.btn-startchat, a.op-btn-chat");
                if (chatBtn.count() == 0) {
                    log.warn("未找到【继续沟通/立即沟通】按钮，跳过发送图片简历");
                    return false;
                }
                chatBtn.first().click();
                page.waitForURL("**/web/geek/chat**", new Page.WaitForURLOptions().setTimeout(15_000));
            }

            // 1) 解析图片路径（在可能触发文件选择器前就准备好）
            java.nio.file.Path imagePath = resolveResumeImage();

            // 精准定位聊天工具栏内的图片输入，避免匹配到页面其他上传控件
            Locator imgContainer = page.locator("div.btn-sendimg[aria-label='发送图片'], div[aria-label='发送图片'].btn-sendimg");
            Locator imageInput = imgContainer.locator("input[type='file'][accept*='image']").first();
            if (imageInput.count() == 0) {
                // 若未渲染，尝试拦截系统文件选择器；若未出现则普通点击促使 input 出现
                if (imgContainer.count() > 0) {
                    boolean chooserHandled = false;
                    try {
                        com.microsoft.playwright.FileChooser chooser = page.waitForFileChooser(() -> {
                            imgContainer.first().click();
                        });
                        chooser.setFiles(imagePath);
                        chooserHandled = true;
                        log.info("通过 FileChooser 直接提交图片文件，避免系统窗口阻塞");
                    } catch (com.microsoft.playwright.PlaywrightException ignore) {
                        // 未弹出系统文件选择器，继续常规流程
                    }
                    if (!chooserHandled) {
                        PlaywrightUtil.sleep(1);
                        imageInput = imgContainer.locator("input[type='file'][accept*='image']").first();
                    }
                }
            }
            imageInput.waitFor(new Locator.WaitForOptions().setTimeout(10_000));

            // 上传图片
            imageInput.setInputFiles(imagePath);
            PlaywrightUtil.sleep(1);
            return true;
        } catch (Throwable e) {
            log.error("发送图片简历失败：{}", e.getMessage(), e);
            return false;
        }
    }

    private java.nio.file.Path resolveResumeImage() throws Exception {
        URL resourceUrl = Boss.class.getResource("/resume.jpg");
        if (resourceUrl == null) {
            throw new IllegalStateException("资源文件 /resume.jpg 未找到，请将图片放置到 src/main/resources 目录下");
        }
        if ("file".equalsIgnoreCase(resourceUrl.getProtocol())) {
            return java.nio.file.Paths.get(resourceUrl.toURI());
        }
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("resume-", ".jpg");
        try (java.io.InputStream in = Boss.class.getResourceAsStream("/resume.jpg")) {
            if (in == null) {
                throw new IllegalStateException("无法从类路径读取 /resume.jpg 资源");
            }
            java.nio.file.Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return temp;
    }

    /**
     * 检查岗位薪资是否符合预期
     *
     * @return boolean
     * true 不符合预期
     * false 符合预期
     * 期望的最低薪资如果比岗位最高薪资还小，则不符合（薪资给的太少）
     * 期望的最高薪资如果比岗位最低薪资还小，则不符合(要求太高满足不了)
     */
    private boolean isSalaryNotExpected(String salary) {
        try {
            // 1. 如果没有期望薪资范围，直接返回 false，表示"薪资并非不符合预期"
            List<Integer> expectedSalary = config.getExpectedSalary();
            if (!hasExpectedSalary(expectedSalary)) {
                return false;
            }

            // 2. 清理薪资文本（比如去掉 "·15薪"）
            salary = removeYearBonusText(salary);

            // 3. 如果薪资格式不符合预期（如缺少 "K" / "k"），直接返回 true，表示"薪资不符合预期"
            if (!isSalaryInExpectedFormat(salary)) {
                return true;
            }

            // 4. 进一步清理薪资文本，比如去除 "K"、"k"、"·" 等
            salary = cleanSalaryText(salary);

            // 5. 判断是 "月薪" 还是 "日薪"
            String jobType = detectJobType(salary);
            salary = removeDayUnitIfNeeded(salary); // 如果是按天，则去除 "元/天"

            // 6. 解析薪资范围并检查是否超出预期
            Integer[] jobSalaryRange = parseSalaryRange(salary);
            return isSalaryOutOfRange(jobSalaryRange,
                    getMinimumSalary(expectedSalary),
                    getMaximumSalary(expectedSalary),
                    jobType);

        } catch (Exception e) {
            log.error("岗位薪资获取异常！薪资文本【{}】,异常信息【{}】", salary, e.getMessage(), e);
            // 出错时，您可根据业务需求决定返回 true 或 false
            // 这里假设出错时无法判断，视为不满足预期 => 返回 true
            return true;
        }
    }

    /**
     * 是否存在有效的期望薪资范围
     */
    private boolean hasExpectedSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && !expectedSalary.isEmpty();
    }

    /**
     * 去掉年终奖信息，如 "·15薪"、"·13薪"。
     */
    private String removeYearBonusText(String salary) {
        if (salary.contains("薪")) {
            // 使用正则去除 "·任意数字薪"
            return salary.replaceAll("·\\d+薪", "");
        }
        return salary;
    }

    /**
     * 判断是否是按天计薪，如发现 "元/天" 则认为是日薪
     */
    private String detectJobType(String salary) {
        if (salary.contains("元/天")) {
            return "day";
        }
        return "mouth";
    }

    /**
     * 如果是日薪，则去除 "元/天"
     */
    private String removeDayUnitIfNeeded(String salary) {
        if (salary.contains("元/天")) {
            return salary.replaceAll("元/天", "");
        }
        return salary;
    }

    private Integer getMinimumSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && !expectedSalary.isEmpty() ? expectedSalary.get(0) : null;
    }

    private Integer getMaximumSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && expectedSalary.size() > 1 ? expectedSalary.get(1) : null;
    }

    private boolean isSalaryInExpectedFormat(String salaryText) {
        return salaryText.contains("K") || salaryText.contains("k") || salaryText.contains("元/天");
    }

    private String cleanSalaryText(String salaryText) {
        salaryText = salaryText.replace("K", "").replace("k", "");
        int dotIndex = salaryText.indexOf('·');
        if (dotIndex != -1) {
            salaryText = salaryText.substring(0, dotIndex);
        }
        return salaryText;
    }

    private boolean isSalaryOutOfRange(Integer[] jobSalary, Integer miniSalary, Integer maxSalary,
                                       String jobType) {
        if (jobSalary == null) {
            return true;
        }
        if (miniSalary == null) {
            return false;
        }
        if (Objects.equals("day", jobType)) {
            // 期望薪资转为平均每日的工资
            maxSalary = BigDecimal.valueOf(maxSalary).multiply(BigDecimal.valueOf(1000))
                    .divide(BigDecimal.valueOf(21.75), 0, RoundingMode.HALF_UP).intValue();
            miniSalary = BigDecimal.valueOf(miniSalary).multiply(BigDecimal.valueOf(1000))
                    .divide(BigDecimal.valueOf(21.75), 0, RoundingMode.HALF_UP).intValue();
        }
        // 如果职位薪资下限低于期望的最低薪资，返回不符合
        if (jobSalary[1] < miniSalary) {
            return true;
        }
        // 如果职位薪资上限高于期望的最高薪资，返回不符合
        return maxSalary != null && jobSalary[0] > maxSalary;
    }

    public boolean containsDeadStatus(String activeTimeText, List<String> deadStatus) {
        for (String status : deadStatus) {
            if (activeTimeText.contains(status)) {
                return true;// 一旦找到包含的值，立即返回 true
            }
        }
        return false;// 如果没有找到，返回 false
    }

    private void closeChatDialog(Page page) {
        try {
            Locator close = page.locator("i.icon-close");
            if (close.count() > 0) {
                PlaywrightUtil.sleep(1);
                close.first().click();
            }
        } catch (Exception e) {
            log.warn("关闭聊天窗口失败，但投递可能已成功: {}", e.getMessage());
        }
    }

    private String tryGenerateAiGreeting(Page detailPage, String keyword, Job job) {
        if (!Boolean.TRUE.equals(config.getEnableAI())) {
            return null;
        }
        String jd = job.getJobInfo();
        if (jd == null || jd.isEmpty()) {
            return null;
        }
        Locator inputLocator = detailPage.locator("div#chat-input.chat-input[contenteditable='true'], textarea.input-area");
        if (inputLocator.count() == 0 || !inputLocator.first().isVisible()) {
            return null;
        }
        String defaultMessage = readChatInputText(inputLocator.first());
        String aiMessage = generateAiMessage(keyword, job.getJobName(), jd, defaultMessage);
        if (!isValidString(aiMessage) || aiMessage.equals(defaultMessage)) {
            return null;
        }
        return aiMessage;
    }

    private boolean sendChatMessage(Page detailPage, String message) {
        Locator inputLocator = detailPage.locator("div#chat-input.chat-input[contenteditable='true'], textarea.input-area");
        if (inputLocator.count() == 0) {
            return false;
        }
        Locator input = inputLocator.first();
        input.click();
        Object tagObj = input.evaluate("el => el.tagName.toLowerCase()");
        if (tagObj instanceof String && "textarea".equals(tagObj)) {
            input.fill(message);
        } else {
            input.evaluate("(el, msg) => { el.innerText = msg; el.dispatchEvent(new Event('input')); }", message);
        }
        Locator sendText = detailPage.locator("div.send-message, button[type='send'].btn-send, button.btn-send");
        if (sendText.count() == 0) {
            return false;
        }
        sendText.first().click();
        PlaywrightUtil.sleep(1);
        return true;
    }

    private String readChatInputText(Locator input) {
        try {
            Object tagObj = input.evaluate("el => el.tagName.toLowerCase()");
            if (tagObj instanceof String && "textarea".equals(tagObj)) {
                return input.inputValue().trim();
            }
            Object text = input.evaluate("el => (el.innerText || el.textContent || '').trim()");
            return text instanceof String ? ((String) text).trim() : "";
        } catch (Exception e) {
            log.debug("读取聊天输入框内容失败: {}", e.getMessage());
            return "";
        }
    }

    private String generateAiMessage(String keyword, String jobName, String jd, String referenceGreeting) {
        AiEntity aiConfig = aiService.getAiConfig();
        String introduce = (aiConfig != null && aiConfig.getIntroduce() != null) ? aiConfig.getIntroduce() : "";
        String prompt = (aiConfig != null) ? aiConfig.getPrompt() : null;
        String reference = isValidString(referenceGreeting) ? referenceGreeting : "";

        String requestMessage = (prompt != null)
                ? String.format(prompt, introduce, keyword, jobName, jd, reference)
                : buildDefaultPrompt(introduce, keyword, jobName, jd, reference);

        try {
            String result = aiService.sendRequest(requestMessage);
            if (result == null) {
                return null;
            }
            return result.toLowerCase().contains("false") ? null : result;
        } catch (Exception e) {
            log.warn("AI请求失败，将使用 Boss 官网默认招呼语: {}", e.getMessage());
            return null;
        }
    }

    private String buildDefaultPrompt(String introduce, String keyword, String jobName, String jd, String referenceGreeting) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请基于以下信息生成简洁友好的中文打招呼语，不超过60字：\n");
        prompt.append("个人介绍：").append(introduce).append("\n");
        prompt.append("关键词：").append(keyword).append("\n");
        prompt.append("职位名称：").append(jobName).append("\n");
        prompt.append("职位描述：").append(jd).append("\n");
        if (isValidString(referenceGreeting)) {
            prompt.append("参考语（Boss官网默认）：").append(referenceGreeting);
        }
        return prompt.toString();
    }

    private Integer[] parseSalaryRange(String salaryText) {
        try {
            return Arrays.stream(salaryText.split("-")).map(s -> s.replaceAll("[^0-9]", "")) // 去除非数字字符
                    .map(Integer::parseInt) // 转换为Integer
                    .toArray(Integer[]::new); // 转换为Integer数组
        } catch (Exception e) {
            log.error("薪资解析异常！{}", e.getMessage(), e);
        }
        return null;
    }

    private void waitForSliderVerify(Page page) {
        String SLIDER_URL = "https://www.zhipin.com/web/user/safe/verify-slider";
        // 最多等待5分钟（防呆，防止死循环）
        long start = System.currentTimeMillis();
        while (true) {
            String url = page.url();
            if (url != null && url.startsWith(SLIDER_URL)) {
                progressCallback.accept("请手动完成Boss直聘滑块验证，通过后在控制台回车继续...", 0, 0);
                System.out.println("\n【滑块验证】请手动完成Boss直聘滑块验证，通过后在控制台回车继续…");
                try {
                    System.in.read();
                } catch (Exception e) {
                    log.error("等待滑块验证输入异常: {}", e.getMessage());
                }
                PlaywrightUtil.sleep(1);
                // 验证通过后页面url会变，循环再检测一次
                continue;
            }
            if ((System.currentTimeMillis() - start) > 5 * 60 * 1000) {
                throw new RuntimeException("滑块验证超时！");
            }
            break;
        }
    }


    private boolean isLoginRequired() {
        try {
            Locator buttonLocator = page.locator(LOGIN_BTNS);
            if (buttonLocator.count() > 0 && buttonLocator.textContent().contains("登录")) {
                return true;
            }
        } catch (Exception e) {
            try {
                page.locator(PAGE_HEADER).waitFor();
                Locator errorLoginLocator = page.locator(ERROR_PAGE_LOGIN);
                if (errorLoginLocator.count() > 0) {
                    errorLoginLocator.click();
                }
                return true;
            } catch (Exception ex) {
                log.info("没有出现403访问异常");
            }
            log.info("cookie有效，已登录...");
            return false;
        }
        return false;
    }

}
