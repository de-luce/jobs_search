package com.getjobs.worker.job51;

import com.getjobs.application.service.BlacklistService;
import com.getjobs.application.service.Job51Service;
import com.getjobs.worker.utils.JobUtils;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 * 前程无忧自动投递简历 - Playwright版本
 */
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class Job51 {

    // 显式setter，避免对 Lombok 的依赖导致编译问题
    @Setter
    private Page page;

    @Setter
    private Job51Config config;

    @Setter
    private ProgressCallback progressCallback;

    @Setter
    private Supplier<Boolean> shouldStopCallback;

    private final List<String> resultList = new ArrayList<>();
    private final Job51Service job51Service;
    private final BlacklistService blacklistService;
    /** 共享 Page 只注册一次网络监听，避免 prototype Bean 重复挂载 */
    private static final Set<Page> NETWORK_HOOKED_PAGES = ConcurrentHashMap.newKeySet();
    private boolean reachedDailyLimit = false;
    private final java.util.Set<String> processedRequestIds = new java.util.HashSet<>();
    @Getter
    private int currentPageNum = 0;
    // 当前页从JSON拦截到的jobId列表
    private final java.util.List<Long> currentPageJobIds = new java.util.ArrayList<>();

    private static final int DEFAULT_MAX_PAGE = 50;
    private static final String BASE_URL = "https://we.51job.com/pc/search";
    private static final String WE_PC_HOME_URL = "https://we.51job.com/pc/";
    private static final int SEARCH_READY_TIMEOUT_MS = 20000;
    private static final String[] JOB_CHECKBOX_SELECTORS = {
            "div.ick",
            ".j_joblist div.ick",
            ".joblist-item input[type=checkbox]",
            "input[type=checkbox]"
    };
    private static final String[] JOB_LIST_READY_SELECTORS = {
            "div.ick",
            "[class*='jname']",
            "a[href*='jobdetail']",
            ".el-empty",
            "text=暂无职位",
            "text=没有符合条件"
    };

    /** 等待当前关键词的 search-pc 接口响应 */
    private volatile CompletableFuture<Integer> searchApiFuture;
    private volatile int lastSearchApiJobCount = 0;

    /**
     * 进度回调接口
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void accept(String message, Integer current, Integer total);
    }

    /**
     * 准备工作：加载配置、初始化数据
     */
    public void prepare() {
        resultList.clear();
    }

    /**
     * 执行投递任务
     * @return 投递数量
     */
    public int execute() {
        long startTime = System.currentTimeMillis();

        try {
            // 检查配置是否有效
            if (config == null) {
                log.error("[51job] 配置为空，无法执行投递任务");
                sendProgress("配置为空，无法执行投递任务", null, null);
                return 0;
            }
            
            if (config.getKeywords() == null || config.getKeywords().isEmpty()) {
                log.warn("[51job] 关键词列表为空，无法执行投递任务");
                sendProgress("关键词列表为空，请先配置搜索关键词", null, null);
                return 0;
            }
            
            // 遍历所有关键词进行投递
            for (String keyword : config.getKeywords()) {
                if (shouldStop()) {
                    sendProgress("用户取消投递", null, null);
                    break;
                }

                String searchUrl = buildSearchUrl(keyword);
                deliverByKeyword(keyword, searchUrl);
            }

            long duration = System.currentTimeMillis() - startTime;
            String message = String.format("51job投递完成，共投递%d个简历，用时%s",
                resultList.size(), formatDuration(duration));
            sendProgress(message, null, null);

        } catch (Exception e) {
            log.error("51job投递过程出现异常", e);
            sendProgress("投递出现异常: " + e.getMessage(), null, null);
        }

        return resultList.size();
    }

    /**
     * 按关键词投递
     */
    private void deliverByKeyword(String keyword, String searchUrl) {
        try {
            // 收敛日志：不输出关键词级日志，仅保留页级摘要

            // 在跳转前监听 51job 搜索接口，抓取 JSON 并保存到数据库 + 打印诊断日志
            if (page != null && NETWORK_HOOKED_PAGES.add(page)) {
                try {
                    page.onResponse(r -> {
                        try {
                            String url = r.url();
                            if (url != null && url.contains("/api/job/search-pc") && "GET".equalsIgnoreCase(r.request().method())) {
                                int status = 0;
                                try { status = r.status(); } catch (Throwable ignored) {}
                                String text = null;
                                try { text = r.text(); } catch (Throwable ignored) {}
                                int len = text == null ? 0 : text.length();
                                // 基于 URL 的 requestId 做去重，避免重复解析
                                String requestId = null;
                                try {
                                    java.net.URI u = new java.net.URI(url);
                                    String q = u.getQuery();
                                    if (q != null) {
                                        for (String part : q.split("&")) {
                                            int i = part.indexOf('=');
                                            if (i > 0 && "requestId".equals(part.substring(0, i))) {
                                                requestId = java.net.URLDecoder.decode(part.substring(i + 1), java.nio.charset.StandardCharsets.UTF_8);
                                                break;
                                            }
                                        }
                                    }
                                } catch (Exception ignored) {}
                                if (requestId != null && !requestId.isBlank() && processedRequestIds.contains(requestId)) {
                                    return;
                                }
                                if (text != null) {
                                    // 根据 Content-Type 粗判是否为 JSON
                                    boolean isJson = false;
                                    try {
                                        java.util.Map<String, String> headers = r.headers();
                                        if (headers != null) {
                                            String ct = headers.getOrDefault("content-type", headers.get("Content-Type"));
                                            if (ct != null && ct.toLowerCase().contains("json")) isJson = true;
                                        }
                                    } catch (Throwable ignored) {}
                                    if (isJson) {
                                        // 解析并保存到数据库
                                        job51Service.parseAndPersistJob51SearchJson(text);
                                        // 📋 提取当前页的jobId列表并缓存
                                        List<Long> jobIds = extractJobIdsFromJson(text);
                                        if (jobIds != null && !jobIds.isEmpty()) {
                                            synchronized (currentPageJobIds) {
                                                currentPageJobIds.clear();
                                                currentPageJobIds.addAll(jobIds);
                                            }
                                        }
                                        CompletableFuture<Integer> future = searchApiFuture;
                                        if (future != null && !future.isDone()) {
                                            future.complete(jobIds == null ? 0 : jobIds.size());
                                        }
                                        if (requestId != null && !requestId.isBlank()) processedRequestIds.add(requestId);
                                    } // 非JSON静默跳过
                                }
                            }
                        } catch (Throwable e) {
                            // 静默错误
                        }
                    });
                } catch (Throwable e) {
                    // 静默错误
                    NETWORK_HOOKED_PAGES.remove(page);
                }
            }

            // 导航到搜索页面（从 login/www 进入时需先预热 we 子域）
            navigateToSearchPage(searchUrl);

            recoverBlankSearchPage(searchUrl, keyword);

            if (!waitForSearchPageReady()) {
                sendProgress("搜索页加载失败，跳过关键词: " + keyword, null, null);
                return;
            }

            // 检查是否需要登录
            if (checkNeedLogin()) {
                sendProgress("需要重新登录，跳过关键词: " + keyword, null, null);
                return;
            }

            // 点击排序选项（选择第一个排序方式）
            try {
                Locator sortOptions = page.locator("div.ss");
                if (sortOptions.count() > 0) {
                    sortOptions.first().click();
                    PlaywrightUtil.sleep(1);
                }
            } catch (Exception e) { /* 静默 */ }

            // 遍历页面投递
            for (int pageNum = 1; pageNum <= DEFAULT_MAX_PAGE; pageNum++) {
                if (shouldStop()) {
                    sendProgress("用户取消投递", null, null);
                    return;
                }

                sendProgress(String.format("正在投递第%d页", pageNum), pageNum, DEFAULT_MAX_PAGE);
                currentPageNum = pageNum;

                // 跳转到指定页码
                if (pageNum > 1 && !jumpToPage(pageNum)) {
                    break;
                }

                PlaywrightUtil.sleep(2);

                // 检查是否出现访问验证
                if (checkAccessVerification()) {
                    sendProgress("出现访问验证，停止投递", null, null);
                    return;
                }

                // 检测“无职位”文案，提前结束当前关键词
                try {
                    if (detectNoJobs51job()) {
                        sendProgress("该关键词暂无职位，提前结束", null, null);
                        break;
                    }
                } catch (Exception ignored) {}

                // 投递当前页面的所有职位
                int deliveredOnPage = deliverCurrentPage();
                if (deliveredOnPage == 0) {
                    if (pageNum == 1) {
                        sendProgress("当前页未找到可投递岗位，请确认已登录且搜索结果已正常显示", null, null);
                    }
                    break;
                }
                if (reachedDailyLimit) break;

                PlaywrightUtil.sleep(3);
            }

            // 关键词完成不输出日志
        } catch (Exception e) { /* 静默 */ }
    }

    /**
     * 投递当前页面的所有职位
     * @return 本页实际选中的岗位数
     */
    private int deliverCurrentPage() {
        try {
            PlaywrightUtil.sleep(1);

            // 查找所有职位的checkbox
            Locator checkboxes = findJobCheckboxes();
            int jobCount = checkboxes.count();
            if (jobCount == 0) {
                log.warn("[51job] 当前页未找到岗位勾选框，选择器可能失效或页面尚未渲染完成");
                return 0;
            }

            // 查找职位名称和公司名称
            Locator titles = findJobTitles();
            Locator companies = findJobCompanies();
            int selectedCount = 0;
            java.util.List<Long> filteredIds = new java.util.ArrayList<>();

            // 选中未命中黑名单的职位
            for (int i = 0; i < jobCount; i++) {
                if (shouldStop()) {
                    return 0;
                }

                try {
                    String title = i < titles.count() ? titles.nth(i).textContent() : "未知职位";
                    String company = i < companies.count() ? companies.nth(i).textContent() : "未知公司";
                    String matchedBlacklist = blacklistService.findMatchedCompany(company);
                    if (matchedBlacklist != null) {
                        log.info("被过滤：公司名命中全局黑名单「{}」 | 公司：{} | 岗位：{}", matchedBlacklist, company, title);
                        synchronized (currentPageJobIds) {
                            if (i < currentPageJobIds.size()) {
                                filteredIds.add(currentPageJobIds.get(i));
                            }
                        }
                        continue;
                    }

                    Locator checkbox = checkboxes.nth(i);
                    // 使用JavaScript点击，避免元素被遮挡
                    checkbox.evaluate("el => el.click()");
                    selectedCount++;

                    String jobInfo = company + " | " + title;
                    resultList.add(jobInfo);
//                    log.info("选中: {}", jobInfo);
                } catch (Exception e) { /* 静默 */ }
            }

            if (!filteredIds.isEmpty()) {
                try { job51Service.markFilteredBatch(filteredIds); } catch (Exception ignore) {}
            }

            if (selectedCount == 0) {
                log.info("[51job] 当前页职位均命中黑名单或无可选岗位，跳过批量投递");
                return 0;
            }

            PlaywrightUtil.sleep(1);

            // 滚动到页面顶部
            page.evaluate("window.scrollTo(0, 0)");
            PlaywrightUtil.sleep(1);

            // 点击批量投递按钮
            clickBatchDeliverButton();

            PlaywrightUtil.sleep(2);

            // 处理投递成功弹窗，并确保遮罩层完全关闭，避免影响翻页/下一轮勾选
            handleDeliverySuccessDialog();
            handleSeparateDeliveryDialog();
            dismissDeliveryDialogs(5);

            return selectedCount;

        } catch (Exception e) {
            log.error("投递当前页面失败", e);
            return 0;
        }
    }

    /**
     * 等待搜索页 Vue 应用与岗位列表渲染完成
     */
    private boolean waitForSearchPageReady() {
        sendProgress("等待搜索结果加载...", null, null);
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(SEARCH_READY_TIMEOUT_MS));
        } catch (Exception e) {
            log.debug("[51job] 等待 DOMContentLoaded 超时: {}", e.getMessage());
        }

        long deadline = System.currentTimeMillis() + SEARCH_READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (shouldStop()) {
                return false;
            }
            try {
                Object appLen = page.evaluate("() => (document.querySelector('#app')?.innerHTML?.length) || 0");
                if (appLen instanceof Number && ((Number) appLen).intValue() > 200) {
                    break;
                }
            } catch (Exception ignored) {}
            PlaywrightUtil.sleepMillis(500);
        }

        CompletableFuture<Integer> apiFuture = searchApiFuture;
        if (apiFuture != null) {
            long apiDeadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < apiDeadline) {
                if (shouldStop()) {
                    return false;
                }
                if (apiFuture.isDone()) {
                    try {
                        Integer count = apiFuture.get(0, TimeUnit.SECONDS);
                        lastSearchApiJobCount = count != null ? count : 0;
                        log.info("[51job] 搜索接口返回 {} 个岗位", lastSearchApiJobCount);
                    } catch (Exception e) {
                        log.warn("[51job] 读取搜索接口结果失败: {}", e.getMessage());
                    }
                    break;
                }
                PlaywrightUtil.sleepMillis(200);
            }
            if (!apiFuture.isDone()) {
                log.warn("[51job] 等待搜索接口超时");
            }
        }

        for (String selector : JOB_LIST_READY_SELECTORS) {
            if (shouldStop()) {
                return false;
            }
            try {
                page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(5000));
                log.info("[51job] 搜索结果已就绪，匹配选择器: {}", selector);
                PlaywrightUtil.sleep(1);
                return true;
            } catch (Exception ignored) {}
        }

        if (lastSearchApiJobCount > 0) {
            log.warn("[51job] 接口返回 {} 个岗位，但 DOM 未匹配常见选择器，继续尝试投递", lastSearchApiJobCount);
            PlaywrightUtil.sleep(2);
            return true;
        }

        log.warn("[51job] 搜索页未加载出岗位列表，url={} appLen={}", safeCurrentUrl(), readAppContentLength());
        return false;
    }

    private String safeCurrentUrl() {
        try {
            return page.url();
        } catch (Exception e) {
            return "";
        }
    }

    private int readAppContentLength() {
        try {
            Object appLen = page.evaluate("() => (document.querySelector('#app')?.innerHTML?.length) || 0");
            return appLen instanceof Number ? ((Number) appLen).intValue() : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    private Locator findJobCheckboxes() {
        for (String selector : JOB_CHECKBOX_SELECTORS) {
            Locator locator = page.locator(selector);
            if (locator.count() > 0) {
                return locator;
            }
        }
        return page.locator("div.ick");
    }

    private Locator findJobTitles() {
        String[] selectors = {
                "[class*='jname text-cut']",
                "[class*='jname']",
                "a[href*='jobdetail']",
                "[class*='jobname']"
        };
        for (String selector : selectors) {
            Locator locator = page.locator(selector);
            if (locator.count() > 0) {
                return locator;
            }
        }
        return page.locator("[class*='jname text-cut']");
    }

    private Locator findJobCompanies() {
        String[] selectors = {
                "[class*='cname text-cut']",
                "[class*='cname']",
                "[class*='company']"
        };
        for (String selector : selectors) {
            Locator locator = page.locator(selector);
            if (locator.count() > 0) {
                return locator;
            }
        }
        return page.locator("[class*='cname text-cut']");
    }

    /**
     * 点击批量投递按钮
     */
    private void clickBatchDeliverButton() {
        int retryCount = 0;
        boolean success = false;

        while (!success && retryCount < 5) {
            try {
                if (shouldStop()) {
                    return;
                }

                // 查找批量投递按钮
                Locator parent = page.locator("div.tabs_in");
                Locator buttons = parent.locator("button.p_but");

                if (buttons.count() > 1) {
                    PlaywrightUtil.sleep(1);
                    buttons.nth(1).click();
                    
                    // 🚨 点击后立即检测“日投递上限”提示（短暂出现，需快速多次检测）
                    for (int i = 0; i < 10; i++) {
                        try { Thread.sleep(200); } catch (InterruptedException ignored) {} // 每200ms检测一次
                        if (detectDailyLimitToast51job()) {
                            reachedDailyLimit = true;
                            log.warn("点击投递按钮后，检测到 51job 日投递上限提示，停止投递");
                            sendProgress("检测到日投递上限，任务已停止", null, null);
                            return;
                        }
                    }
                    
                    success = true;
                } else {
                    break;
                }
            } catch (Exception e) {
                retryCount++;
                PlaywrightUtil.sleep(1);
            }
        }
    }

    /**
     * 处理投递成功弹窗：记录结果 → 关闭弹窗/遮罩，避免影响后续勾选与翻页
     */
    private void handleDeliverySuccessDialog() {
        try {
            // 弹窗可能延迟出现，短暂轮询
            for (int i = 0; i < 10; i++) {
                if (shouldStop()) {
                    return;
                }
                if (hasVisibleDeliveryDialog()) {
                    break;
                }
                PlaywrightUtil.sleepMillis(300);
            }

            Locator successContent = page.locator("//div[@class='successContent']");
            if (successContent.count() > 0) {
                String text = successContent.textContent();
                if (text != null && text.contains("快来扫码下载")) {
                    log.info("检测到下载App弹窗，关闭中...");
                    Locator closeButton = page.locator(
                            ".van-popup__close-icon, .van-icon-cross, [class*='van-popup__close-icon']");
                    if (closeButton.count() > 0) {
                        closeButton.first().click(new Locator.ClickOptions().setForce(true).setTimeout(2000));
                        log.info("成功关闭下载App弹窗");
                    }
                }
            }

            // 兼容提示弹框：投递成功N个，未投递M个
            Locator elDialogBody = page.locator(".el-dialog__body, .el-message-box__message, .el-message-box__content");
            if (elDialogBody.count() > 0) {
                String dialogText = null;
                try {
                    dialogText = elDialogBody.first().innerText();
                } catch (Exception ignored) {}
                if (dialogText != null && (dialogText.contains("投递成功") || dialogText.contains("投递完成"))) {
                    Integer successNum = null;
                    Integer failNum = null;
                    try {
                        java.util.regex.Matcher m1 = java.util.regex.Pattern.compile("投递成功\\D*(\\d+)").matcher(dialogText);
                        if (m1.find()) successNum = Integer.parseInt(m1.group(1));
                        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("未投递\\D*(\\d+)").matcher(dialogText);
                        if (m2.find()) failNum = Integer.parseInt(m2.group(1));
                    } catch (Exception ignored) {}
                    log.info("[51job] 投递结果：成功 {} 个，未投递 {} 个", successNum, failNum);
                    sendProgress(String.format("投递结果：成功 %s 个，未投递 %s 个",
                            successNum == null ? "?" : successNum,
                            failNum == null ? "?" : failNum), null, null);

                    if (successNum != null && successNum > 0) {
                        try {
                            List<Long> deliveredIds = new ArrayList<>();
                            synchronized (currentPageJobIds) {
                                deliveredIds.addAll(currentPageJobIds);
                            }
                            if (!deliveredIds.isEmpty()) {
                                int markCount = Math.min(successNum, deliveredIds.size());
                                List<Long> toMark = deliveredIds.subList(0, markCount);
                                job51Service.markDeliveredBatch(toMark);
                                log.info("[51job] 标记已投递 {} 个职位", toMark.size());
                            } else {
                                log.warn("[51job] 当前页没有缓存的jobId，无法标记投递状态");
                            }
                        } catch (Exception e) {
                            log.warn("[51job] 标记投递状态失败: {}", e.getMessage());
                        }
                    } else {
                        log.warn("[51job] 投递成功数量为0或未解析到，不标记投递状态");
                    }
                }
            }

            dismissDeliveryDialogs(5);

            try {
                if (detectDailyLimitToast51job()) {
                    reachedDailyLimit = true;
                    log.warn("处理成功弹窗后，检测到 51job 日投递上限提示，停止当前页");
                }
            } catch (Exception ignored) {}
        } catch (Exception e) {
            log.debug("未找到投递成功弹窗或处理失败: {}", e.getMessage());
            dismissDeliveryDialogs(3);
        }
    }

    private boolean hasVisibleDeliveryDialog() {
        try {
            Locator dialogs = page.locator(
                    ".el-dialog__wrapper:visible, .el-overlay:visible, .el-message-box__wrapper:visible, " +
                    ".van-popup:visible, .successContent:visible, div.el-dialog:visible");
            return dialogs.count() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 主动关闭投递成功/结果弹窗及残留遮罩，防止挡住后续投递。
     */
    private void dismissDeliveryDialogs(int maxRounds) {
        for (int round = 0; round < maxRounds; round++) {
            if (shouldStop()) {
                return;
            }
            boolean closed = false;
            String[] closeSelectors = {
                    ".el-dialog__footer button:has-text('确定')",
                    ".el-dialog__footer button:has-text('我知道了')",
                    ".el-dialog__footer button:has-text('关闭')",
                    ".el-message-box__btns button:has-text('确定')",
                    ".el-message-box__btns button:has-text('我知道了')",
                    "button:has-text('确定')",
                    "button:has-text('我知道了')",
                    "button:has-text('关闭')",
                    "button.el-dialog__headerbtn",
                    ".el-dialog__headerbtn",
                    "button[aria-label='Close']",
                    "i.el-dialog__close",
                    ".van-popup__close-icon",
                    ".van-icon-cross"
            };
            for (String sel : closeSelectors) {
                try {
                    Locator btn = page.locator(sel);
                    if (btn.count() == 0) {
                        continue;
                    }
                    Locator first = btn.first();
                    if (!first.isVisible()) {
                        continue;
                    }
                    first.click(new Locator.ClickOptions().setForce(true).setTimeout(1500));
                    closed = true;
                    break;
                } catch (Exception ignored) {}
            }

            if (!closed) {
                try {
                    page.evaluate("""
                        (() => {
                          const clickAll = (sel) => document.querySelectorAll(sel).forEach(el => {
                            try { el.click(); } catch (e) {}
                          });
                          clickAll('button.el-dialog__headerbtn');
                          clickAll("button[aria-label='Close']");
                          clickAll('.van-popup__close-icon, .van-icon-cross');
                          document.querySelectorAll('.el-dialog__footer button, .el-message-box__btns button')
                            .forEach(b => {
                              const t = (b.textContent || '').trim();
                              if (t.includes('确定') || t.includes('我知道了') || t.includes('关闭')) {
                                try { b.click(); } catch (e) {}
                              }
                            });
                          // 移除残留遮罩，避免 pointer-events 挡住后续操作
                          document.querySelectorAll('.el-dialog__wrapper, .el-overlay, .el-message-box__wrapper, .v-modal')
                            .forEach(el => {
                              if (el && el.style) {
                                el.style.display = 'none';
                                el.style.pointerEvents = 'none';
                              }
                            });
                        })()
                        """);
                    closed = true;
                } catch (Exception ignored) {}
            }

            try {
                page.keyboard().press("Escape");
            } catch (Exception ignored) {}

            PlaywrightUtil.sleepMillis(400);
            if (!hasVisibleDeliveryDialog()) {
                if (round > 0 || closed) {
                    log.info("[51job] 投递弹窗已关闭");
                }
                return;
            }
        }
        // 最终兜底：隐藏遮罩层
        try {
            page.evaluate("""
                (() => {
                  document.querySelectorAll('.el-dialog__wrapper, .el-overlay, .el-message-box__wrapper, .v-modal, .van-overlay')
                    .forEach(el => { el.remove(); });
                })()
                """);
            log.warn("[51job] 弹窗未能正常关闭，已强制移除遮罩层");
        } catch (Exception ignored) {}
        closeAnyModalOverlays();
    }

    /**
     * 处理单独投递申请弹窗
     */
    private void handleSeparateDeliveryDialog() {
        try {
            Locator dialogContent = page.locator(".el-dialog__body, .el-message-box__message");
            if (dialogContent.count() == 0) {
                return;
            }
            String text = dialogContent.first().innerText();
            if (text != null && text.contains("需要到企业招聘平台单独申请")) {
                log.info("检测到单独投递申请弹窗，关闭中...");
                dismissDeliveryDialogs(3);
            }
        } catch (Exception e) {
            log.debug("未找到单独投递申请弹窗或处理失败: {}", e.getMessage());
        }
    }

    /**
     * 跳转到指定页码
     */
    private boolean jumpToPage(int pageNum) {
        for (int retry = 0; retry < 3; retry++) {
            try {
                if (shouldStop()) {
                    return false;
                }

                // 跳页前先关闭可能遮挡操作的弹框
                dismissDeliveryDialogs(3);

                Locator pageInput = page.locator("#jump_page");
                if (pageInput.count() == 0) {
                    log.warn("未找到页码输入框");
                    return false;
                }

                PlaywrightUtil.sleep(1);
                pageInput.click();
                pageInput.fill("");
                pageInput.fill(String.valueOf(pageNum));

                // 点击跳转按钮
                Locator jumpButton = page.locator("#app > div > div.post > div > div > div.j_result > div > div:nth-child(2) > div > div.bottom-page > div > div > span.jumpPage");
                if (jumpButton.count() > 0) {
                    jumpButton.click();
                }

                // 滚动到页面顶部
                page.evaluate("window.scrollTo(0, 0)");
                PlaywrightUtil.sleep(2);

                log.info("成功跳转到第{}页", pageNum);
                return true;
            } catch (Exception e) {
                log.warn("跳转到第{}页失败，重试第{}次: {}", pageNum, retry + 1, e.getMessage());
                PlaywrightUtil.sleep(1);

                // 检查是否出现异常，如果出现则刷新页面
                if (checkAccessVerification()) {
                    return false;
                }
                page.reload();
                PlaywrightUtil.sleep(2);
            }
        }
        return false;
    }

    /**
     * 统一关闭可能出现的弹框覆盖层（ElementUI/VanPopup 等）。
     */
    private void closeAnyModalOverlays() {
        try {
            boolean closedOnce = false;
            for (int t = 0; t < 3; t++) {
                boolean closedThisRound = false;
                Locator headerClose = page.locator("button.el-dialog__headerbtn, button[aria-label='Close']");
                if (headerClose.count() > 0 && headerClose.first().isVisible()) {
                    try {
                        headerClose.first().click(new Locator.ClickOptions().setForce(true).setTimeout(2000));
                        closedThisRound = true;
                    } catch (Exception ignored) {}
                }
                // 直接点击关闭图标或其父按钮
                Locator iconClose = page.locator("i.el-dialog__close.el-icon.el-icon-close");
                if (iconClose.count() > 0 && iconClose.first().isVisible()) {
                    try {
                        iconClose.first().evaluate("el => el.parentElement && el.parentElement.click()");
                        closedThisRound = true;
                    } catch (Exception ignored) {}
                }
                Locator okBtn = page.locator(".el-dialog__footer button:has-text('确定'), .el-message-box__btns button:has-text('确定')");
                if (okBtn.count() > 0 && okBtn.first().isVisible()) {
                    okBtn.first().click();
                    closedThisRound = true;
                }
                // JS 一次性点击所有可能的关闭按钮，作为强兜底
                if (!closedThisRound) {
                    try {
                        page.evaluate("document.querySelectorAll('button.el-dialog__headerbtn, button[aria-label=\\'Close\\']').forEach(b=>b.click())");
                    } catch (Exception ignored) {}
                }
                Locator popupClose = page.locator(".van-popup__close-icon, .van-icon-cross");
                if (popupClose.count() > 0 && popupClose.first().isVisible()) {
                    popupClose.first().click();
                    closedThisRound = true;
                }
                if (!closedThisRound) break;
                closedOnce = true;
                PlaywrightUtil.sleep(1);
            }
            if (closedOnce) {
                // 等待弹层移除
                try { page.waitForSelector(".el-dialog__wrapper, .van-popup", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.DETACHED).setTimeout(2000)); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.debug("关闭弹框覆盖层失败: {}", e.getMessage());
        }
    }

    /**
     * 检查是否需要登录
     */
    private boolean checkNeedLogin() {
        try {
            Locator loginElement = page.locator("//a[contains(@class, 'uname')]");
            if (loginElement.count() > 0) {
                String text = loginElement.textContent();
                return text != null && text.contains("登录");
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检测 51job 页面是否出现“日投递上限”提示的浮框（短暂存在，需及时检查）。
     */
    private boolean detectDailyLimitToast51job() {
        try {
            String[] kws = new String[]{
                    "今日投递太多", "您今日投递太多", "休息一下明天再来", "达到上限", "次数过多"
            };
            for (String kw : kws) {
                Locator textToast = page.locator("text=" + kw);
                if (textToast.count() > 0 && textToast.first().isVisible()) {
                    return true;
                }
            }
            Locator msg = page.locator(".el-message, .el-message--info, .toast, .message, div[role='alert'], .el-notification__content");
            if (msg.count() > 0) {
                java.util.List<String> texts = new java.util.ArrayList<>();
                try { texts = msg.allInnerTexts(); } catch (Exception ignored) {}
                for (String t : texts) {
                    if (t == null) continue;
                    String tt = t.replace('\n', ' ').trim();
                    for (String kw : kws) {
                        if (tt.contains(kw)) return true;
                    }
                }
            }
            Object foundObj = page.evaluate("() => { const kws = ['今日投递太多','您今日投递太多','休息一下明天再来','达到上限','次数过多']; const bodyText = document.body ? (document.body.innerText || '') : ''; return kws.some(k=>bodyText.includes(k)); }");
            if (foundObj instanceof Boolean) {
                return (Boolean) foundObj;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查是否出现访问验证
     */
    private boolean checkAccessVerification() {
        try {
            Locator wafTitle = page.locator("//p[@class='waf-nc-title']");
            Locator wafScript = page.locator("script[name^='aliyunwaf_']");
            Locator verifyText = page.locator("text=访问验证, text=请按住滑块");
            if ((wafTitle.count() > 0 && wafTitle.first().isVisible()) || wafScript.count() > 0 || verifyText.count() > 0) {
                log.error("出现访问验证，需要手动处理");
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检测 51job 页面是否显示“无职位/没有符合条件的职位”等提示。
     */
    private boolean detectNoJobs51job() {
        try {
            String[] kws = new String[]{
                    "暂无职位", "没有符合条件的职位", "暂无符合条件职位", "暂无符合职位", "暂无相关职位"
            };
            for (String kw : kws) {
                Locator t = page.locator("text=" + kw);
                if (t.count() > 0 && t.first().isVisible()) {
                    return true;
                }
            }
            // 常见空态容器（若存在则进一步通过文本确认）
            Locator empty = page.locator(".el-empty, .empty, .no-result, .no_res");
            if (empty.count() > 0) {
                java.util.List<String> texts = new java.util.ArrayList<>();
                try { texts = empty.allInnerTexts(); } catch (Exception ignored) {}
                for (String t : texts) {
                    if (t == null) continue;
                    String tt = t.replace('\n', ' ').trim();
                    for (String kw : kws) {
                        if (tt.contains(kw)) return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 进入 51job 搜索页。从 login/www 子域跳入 we 子域时，需先预热，否则 Vue 容易白屏。
     */
    private void navigateToSearchPage(String searchUrl) {
        try {
            page.setExtraHTTPHeaders(java.util.Collections.emptyMap());
        } catch (Exception ignored) {}

        String current = safeCurrentUrl();
        boolean fromLegacyEntry = needsWeDomainWarmUp(current);

        resetSearchApiFuture();

        try {
            if (fromLegacyEntry) {
                log.info("[51job] 当前在 {}，先进入 we 子域再打开搜索页", current);
                sendProgress("正在进入51job搜索站点...", null, null);
                page.navigate(WE_PC_HOME_URL, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(60000));
                waitForAppContent(8000);
                resetSearchApiFuture();
            }

            page.navigate(searchUrl, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(60000));
            log.info("[51job] 已打开搜索页: {}", searchUrl);
        } catch (Exception e) {
            log.warn("[51job] 常规跳转搜索页失败，尝试 location 硬跳转: {}", e.getMessage());
            try {
                resetSearchApiFuture();
                page.evaluate("url => window.location.assign(url)", searchUrl);
                page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                        new Page.WaitForLoadStateOptions().setTimeout(60000));
            } catch (Exception ex) {
                log.error("[51job] 硬跳转搜索页失败", ex);
            }
        }
    }

    private boolean needsWeDomainWarmUp(String url) {
        if (url == null || url.isBlank()) {
            return true;
        }
        if (url.contains("we.51job.com")) {
            return false;
        }
        return url.contains("51job.com") || url.contains("127.0.0.1") || url.startsWith("about:");
    }

    /**
     * 搜索页偶发白屏时重载，并尝试在搜索框补填关键词
     */
    private void recoverBlankSearchPage(String searchUrl, String keyword) {
        if (waitForAppContent(8000)) {
            return;
        }

        log.warn("[51job] 搜索页长时间未渲染，尝试重新加载");
        sendProgress("搜索页空白，正在重新加载...", null, null);
        try {
            resetSearchApiFuture();
            page.reload(new Page.ReloadOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(60000));
            waitForAppContent(10000);
        } catch (Exception e) {
            log.debug("[51job] 重新加载搜索页失败: {}", e.getMessage());
        }

        tryFillSearchKeyword(keyword);

        if (!waitForAppContent(5000) && searchUrl != null) {
            try {
                navigateToSearchPage(searchUrl);
                tryFillSearchKeyword(keyword);
                waitForAppContent(8000);
            } catch (Exception ignored) {}
        }
    }

    private void resetSearchApiFuture() {
        searchApiFuture = new CompletableFuture<>();
        lastSearchApiJobCount = 0;
    }

    private boolean waitForAppContent(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (shouldStop()) {
                return false;
            }
            try {
                Object appLen = page.evaluate("() => (document.querySelector('#app')?.innerHTML?.length) || 0");
                int len = appLen instanceof Number ? ((Number) appLen).intValue() : 0;
                if (len > 200) {
                    return true;
                }
            } catch (Exception ignored) {}
            PlaywrightUtil.sleepMillis(400);
        }
        return false;
    }

    private void tryFillSearchKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        String[] selectors = {
            "input[placeholder*='搜索']",
            "input[placeholder*='关键词']",
            "input[type='search']",
            ".search-input input",
            "#keyword"
        };
        for (String sel : selectors) {
            try {
                Locator input = page.locator(sel);
                if (input.count() > 0) {
                    input.first().fill(keyword);
                    input.first().press("Enter");
                    PlaywrightUtil.sleep(2);
                    return;
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * 构建搜索URL
     */
    private String buildSearchUrl(String keyword) {
        StringBuilder query = new StringBuilder();
        query.append(JobUtils.appendListParam("jobArea", config.getJobArea()));
        query.append(JobUtils.appendListParam("salary", config.getSalary()));
        query.append("&keyword=").append(URLEncoder.encode(keyword, StandardCharsets.UTF_8));
        String q = query.toString();
        if (q.startsWith("&")) {
            q = q.substring(1);
        }
        if (q.isEmpty()) {
            return BASE_URL;
        }
        return BASE_URL + "?" + q;
    }

    /**
     * 采集当前页所有岗位的 jobId（解析 jobdetail 链接/数据属性）
     */
    private List<Long> collectJobIdsOnPage() {
        List<Long> ids = new ArrayList<>();
        try {
            // 1) 解析常见 jobdetail 链接形态
            Locator anchors = page.locator(
                    "a[href*='/pc/jobdetail?jobId='], " +
                    "a[href*='/pc/jobdetail'], " +
                    "a[href*='jobs.51job.com/'], " +
                    "a.jname[href]"
            );
            int count = anchors.count();
            for (int i = 0; i < count; i++) {
                try {
                    String href = anchors.nth(i).getAttribute("href");
                    Long id = parseJobIdFromHref(href);
                    if (id != null) ids.add(id);
                } catch (Exception ignored) {}
            }

            // 2) 解析卡片上的数据属性（部分页面存在）
            try {
                Locator cards = page.locator("[data-jobid], [data-analysis-jobid], [data-job-id]");
                int c = cards.count();
                for (int i = 0; i < c; i++) {
                    try {
                        String v = null;
                        Locator card = cards.nth(i);
                        v = v == null ? card.getAttribute("data-jobid") : v;
                        v = v == null ? card.getAttribute("data-analysis-jobid") : v;
                        v = v == null ? card.getAttribute("data-job-id") : v;
                        if (v != null) {
                            try {
                                Long id = Long.parseLong(v.replaceAll("[^0-9]", ""));
                                if (id != null) ids.add(id);
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}

            // 去重
            java.util.Set<Long> uniq = new java.util.LinkedHashSet<>(ids);
            ids = new java.util.ArrayList<>(uniq);

            // 记录采集到的数量与部分样例，便于诊断
            try {
                if (!ids.isEmpty()) {
                    String sample = ids.stream().limit(5).map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
                    log.info("[51job] 当前页采集到 {} 个 jobId, 示例: {}", ids.size(), sample);
                } else {
                    // 采集为空：按用户约定视为达到投递上限/页面结构变化，立即通知并停止
                    log.warn("[51job] 当前页未采集到任何 jobId，可能页面结构变化或选择器不匹配");
                    // 向前端推送警告，便于按钮重置
                    sendProgress("[51job] 当前页未采集到任何 jobId，可能页面结构变化或选择器不匹配", null, null);
                    // 设置达上限标记，外层循环将终止
                    reachedDailyLimit = true;
                }
            } catch (Exception ignored) {}
        } catch (Exception e) {
            log.debug("采集当前页 jobId 失败: {}", e.getMessage());
        }
        return ids;
    }

    private Long parseJobIdFromHref(String href) {
        if (href == null || href.isEmpty()) return null;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&]jobId=(\\d+)").matcher(href);
            if (m.find()) {
                return Long.parseLong(m.group(1));
            }
            java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("/(\\d+)\\.html").matcher(href);
            if (m2.find()) {
                return Long.parseLong(m2.group(1));
            }
            // 兜底：从路径段中找较长数字片段
            java.util.regex.Matcher m3 = java.util.regex.Pattern.compile("(\\d{5,})").matcher(href);
            if (m3.find()) {
                return Long.parseLong(m3.group(1));
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 格式化时长
     */
    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        if (hours > 0) {
            return String.format("%d小时%d分钟", hours, minutes % 60);
        } else if (minutes > 0) {
            return String.format("%d分钟%d秒", minutes, seconds % 60);
        } else {
            return String.format("%d秒", seconds);
        }
    }

    /**
     * 发送进度消息
     */
    private void sendProgress(String message, Integer current, Integer total) {
        if (progressCallback != null) {
            progressCallback.accept(message, current, total);
        }
    }

    /**
     * 检查是否应该停止
     */
    private boolean shouldStop() {
        return shouldStopCallback != null && shouldStopCallback.get();
    }

    /**
     * 从JSON文本中提取jobId列表
     */
    private List<Long> extractJobIdsFromJson(String json) {
        List<Long> jobIds = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return jobIds;
        }
        
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
            
            // 兼容多种列表命名
            com.fasterxml.jackson.databind.JsonNode list = root.path("data").path("items");
            if (!list.isArray()) list = root.path("data").path("jobList");
            if (!list.isArray()) list = root.path("data").path("list");
            if (!list.isArray()) list = root.path("data").path("jobs");
            if (!list.isArray()) list = root.path("resultbody").path("job").path("items");
            if (!list.isArray()) list = root.path("job").path("items");
            if (!list.isArray()) list = root.path("resultbody").path("items");
            
            if (!list.isArray()) {
                return jobIds;
            }
            
            // 提取每个jobId
            for (com.fasterxml.jackson.databind.JsonNode item : list) {
                com.fasterxml.jackson.databind.JsonNode jobIdNode = item.path("jobId");
                if (!jobIdNode.isMissingNode() && !jobIdNode.isNull()) {
                    try {
                        Long jobId = jobIdNode.asLong();
                        if (jobId != null && jobId > 0) {
                            jobIds.add(jobId);
                        }
                    } catch (Exception e) {
                        // 忽略单个解析失败
                    }
                }
            }
            
        } catch (Exception e) {
            log.warn("[51job] 解析JSON提取jobId失败: {}", e.getMessage());
        }
        
        return jobIds;
    }
}
