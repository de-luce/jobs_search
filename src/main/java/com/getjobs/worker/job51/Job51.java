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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    /** 当前页 jobId → 公司名（优先用 API，避免 DOM 下标错位） */
    private final Map<Long, String> currentPageCompanyByJobId = new HashMap<>();

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
                            String method = null;
                            try { method = r.request().method(); } catch (Throwable ignored) {}
                            if (!Job51SearchApi.isSearchPcRequest(url, method)) {
                                return;
                            }
                            int status = 0;
                            try { status = r.status(); } catch (Throwable ignored) {}
                            if (status != 200) {
                                return;
                            }
                            String text = null;
                            try { text = r.text(); } catch (Throwable ignored) {}
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
                            if (text == null) {
                                return;
                            }
                            String contentType = null;
                            try {
                                java.util.Map<String, String> headers = r.headers();
                                if (headers != null) {
                                    contentType = headers.getOrDefault("content-type", headers.get("Content-Type"));
                                }
                            } catch (Throwable ignored) {}
                            if (!Job51SearchApi.looksLikeJsonBody(contentType, text)) {
                                return;
                            }
                            // 解析并保存到数据库
                            job51Service.parseAndPersistJob51SearchJson(text);
                            // 提取当前页 jobId + 公司名并缓存
                            cachePageJobsFromJson(text);
                            List<Long> jobIds;
                            synchronized (currentPageJobIds) {
                                jobIds = new ArrayList<>(currentPageJobIds);
                            }
                            CompletableFuture<Integer> future = searchApiFuture;
                            if (future != null && !future.isDone()) {
                                future.complete(jobIds == null ? 0 : jobIds.size());
                            }
                            if (requestId != null && !requestId.isBlank()) {
                                processedRequestIds.add(requestId);
                            }
                        } catch (Throwable e) {
                            log.warn("[51job] 处理搜索接口响应失败: {}", e.getMessage());
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

            // 遍历页面投递：先投当前页，再点「下一页」，不再依赖 #jump_page 跳页
            for (int pageNum = 1; pageNum <= DEFAULT_MAX_PAGE; pageNum++) {
                if (shouldStop()) {
                    sendProgress("用户取消投递", null, null);
                    return;
                }

                sendProgress(String.format("正在投递第%d页", pageNum), pageNum, DEFAULT_MAX_PAGE);
                currentPageNum = pageNum;

                PlaywrightUtil.sleep(1);

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
                if (deliveredOnPage == 0 && pageNum == 1) {
                    sendProgress("当前页未找到可投递岗位，请确认已登录且搜索结果已正常显示", null, null);
                }
                if (reachedDailyLimit) {
                    break;
                }

                if (pageNum >= DEFAULT_MAX_PAGE) {
                    break;
                }
                if (!goToNextPage()) {
                    log.info("[51job] 无法继续翻页，结束当前关键词");
                    sendProgress("已到最后一页或找不到下一页", pageNum, DEFAULT_MAX_PAGE);
                    break;
                }
                PlaywrightUtil.sleep(2);
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
            java.util.List<Long> selectedIds = new java.util.ArrayList<>();

            // 若接口缓存的 jobId 为空，尝试从 DOM 补采
            List<Long> pageJobIds;
            synchronized (currentPageJobIds) {
                pageJobIds = new ArrayList<>(currentPageJobIds);
            }
            if (pageJobIds.isEmpty()) {
                try {
                    pageJobIds = collectJobIdsOnPage();
                    if (pageJobIds != null && !pageJobIds.isEmpty()) {
                        synchronized (currentPageJobIds) {
                            currentPageJobIds.clear();
                            currentPageJobIds.addAll(pageJobIds);
                        }
                    }
                } catch (Exception ignored) {}
            }

            // 选中未命中黑名单的职位
            Map<Long, String> companyByJobId;
            synchronized (currentPageCompanyByJobId) {
                companyByJobId = new HashMap<>(currentPageCompanyByJobId);
            }
            for (int i = 0; i < jobCount; i++) {
                if (shouldStop()) {
                    return 0;
                }

                try {
                    String title = i < titles.count() ? titles.nth(i).textContent() : "未知职位";
                    Long jobId = (i < pageJobIds.size()) ? pageJobIds.get(i) : null;
                    String company = resolveJob51CompanyName(jobId, i, companies, companyByJobId);
                    if (BlacklistService.isPlaceholderCompany(company)) {
                        log.info("无法解析公司名，跳过勾选以防黑名单漏拦 | 岗位：{} | jobId：{}", title, jobId);
                        continue;
                    }
                    String matchedBlacklist = blacklistService.findMatchedCompany(company);
                    if (matchedBlacklist != null) {
                        log.info("被过滤：公司名命中全局黑名单「{}」 | 公司：{} | 岗位：{}", matchedBlacklist, company, title);
                        if (jobId != null) {
                            filteredIds.add(jobId);
                        }
                        continue;
                    }

                    Locator checkbox = checkboxes.nth(i);
                    // 使用JavaScript点击，避免元素被遮挡
                    checkbox.evaluate("el => el.click()");
                    selectedCount++;
                    if (jobId != null) {
                        selectedIds.add(jobId);
                    }

                    String jobInfo = company + " | " + title;
                    resultList.add(jobInfo);
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

            // 「一键投递」在列表上方工具栏：勾选后常停在底部，需先滚上去才看得见
            ensureDeliverToolbarVisible();

            // 点击批量投递按钮
            if (!clickBatchDeliverButton()) {
                log.warn("[51job] 已勾选 {} 个岗位，但未找到/未点到批量投递按钮", selectedCount);
                sendProgress("已勾选岗位但未点到投递按钮，请检查页面是否变更", null, null);
                dumpVisibleActionButtons();
                return 0;
            }

            PlaywrightUtil.sleep(2);

            // 批量投递后通常只有一个结果弹窗：解析 → 更新状态 → 关闭
            handleDeliverySuccessDialog(selectedIds);
            handleSeparateDeliveryDialog();
            dismissDeliveryDialogs(3);

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
     * 「一键投递」在列表上方 tabs 工具栏。滑到页面底部时会滚出视口，需先滚上去再点击。
     */
    private void ensureDeliverToolbarVisible() {
        try {
            page.evaluate("""
                (() => {
                  window.scrollTo({ top: 0, left: 0, behavior: 'instant' });
                  document.documentElement.scrollTop = 0;
                  document.body.scrollTop = 0;
                  // 滚动常见列表容器
                  document.querySelectorAll('.j_joblist, .joblist, [class*="joblist"], .content, main')
                    .forEach(el => { try { el.scrollTop = 0; } catch (e) {} });
                  // 若存在「一键投递/批量投递」按钮，滚到视口中部（即使原先在视口外）
                  const keywords = ['一键投递', '批量投递', '申请职位', '投递简历'];
                  const nodes = Array.from(document.querySelectorAll('button, a, .p_but, [class*="p_but"]'));
                  const target = nodes.find(el => {
                    const t = (el.innerText || el.textContent || '').replace(/\\s+/g, '');
                    return keywords.some(k => t.includes(k));
                  });
                  if (target) {
                    target.scrollIntoView({ behavior: 'instant', block: 'center', inline: 'nearest' });
                  } else {
                    const bar = document.querySelector('div.tabs_in, .tabs_in, [class*="batch"], [class*="apply"]');
                    if (bar) bar.scrollIntoView({ behavior: 'instant', block: 'center' });
                  }
                })()
                """);
        } catch (Exception e) {
            log.debug("[51job] 滚回投递工具栏失败: {}", e.getMessage());
            try { page.evaluate("window.scrollTo(0, 0)"); } catch (Exception ignored) {}
        }
        PlaywrightUtil.sleepMillis(500);
    }

    /**
     * 点击批量投递按钮。选择器会随站点改版变化，按文案多兜底。
     * @return 是否成功点击
     */
    private boolean clickBatchDeliverButton() {
        String[] candidates = {
                "button:has-text(\"一键投递\")",
                "button.p_but:has-text(\"一键投递\")",
                "div.tabs_in button.p_but:has-text(\"一键投递\")",
                "button:has-text(\"批量投递\")",
                "button:has-text(\"申请职位\")",
                "button:has-text(\"投递简历\")",
                "a:has-text(\"批量投递\")",
                "div.tabs_in button.p_but:has-text(\"投递\")",
                "div.tabs_in button.p_but:has-text(\"申请\")",
                "button.p_but:has-text(\"投递\")",
                "button.p_but:has-text(\"申请\")",
                "[class*='apply'] button:has-text(\"投递\")",
                "[class*='apply'] button:has-text(\"申请\")",
                "button.el-button--primary:has-text(\"投递\")",
                "button.el-button--primary:has-text(\"申请\")"
        };

        for (int retry = 0; retry < 5; retry++) {
            if (shouldStop()) {
                return false;
            }
            ensureDeliverToolbarVisible();

            for (String sel : candidates) {
                try {
                    Locator btn = page.locator(sel);
                    int n = btn.count();
                    if (n == 0) {
                        continue;
                    }
                    Locator target = btn.first();
                    String text = "";
                    try {
                        text = target.innerText();
                    } catch (Exception ignored) {}
                    // 排除明显不是投递的按钮
                    if (text != null && (text.contains("搜索") || text.contains("登录") || text.contains("注册"))) {
                        continue;
                    }
                    // 不因暂不在视口而跳过：先滚入视野再点（滑到底时原先 isVisible=false 会误跳过）
                    try {
                        target.scrollIntoViewIfNeeded();
                    } catch (Exception ignored) {}
                    PlaywrightUtil.sleepMillis(300);
                    try {
                        target.click(new Locator.ClickOptions().setTimeout(3000));
                    } catch (Exception clickEx) {
                        try {
                            target.click(new Locator.ClickOptions().setForce(true).setTimeout(3000));
                        } catch (Exception forceEx) {
                            target.evaluate("el => el.click()");
                        }
                    }
                    log.info("[51job] 已点击批量投递按钮: sel={} text={}", sel, text == null ? "" : text.trim());
                    sendProgress("已点击批量投递按钮", null, null);

                    for (int i = 0; i < 10; i++) {
                        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                        if (detectDailyLimitToast51job()) {
                            reachedDailyLimit = true;
                            log.warn("点击投递按钮后，检测到 51job 日投递上限提示，停止投递");
                            sendProgress("检测到日投递上限，任务已停止", null, null);
                            return true;
                        }
                    }
                    return true;
                } catch (Exception ignored) {}
            }

            // JS 兜底：优先找「一键投递」，不在视口也先 scrollIntoView 再点
            try {
                Object clicked = page.evaluate("""
                    (() => {
                      const keywords = ['一键投递', '批量投递', '申请职位', '投递简历', '立即投递'];
                      const soft = ['投递', '申请'];
                      const nodes = Array.from(document.querySelectorAll('button, a, span, div.p_but, button.p_but'));
                      const present = (el) => {
                        const s = getComputedStyle(el);
                        const r = el.getBoundingClientRect();
                        return s.visibility !== 'hidden' && s.display !== 'none'
                          && r.width > 0 && r.height > 0;
                      };
                      const pick = (list) => list.find(el => {
                        if (!present(el)) return false;
                        const t = (el.innerText || el.textContent || '').replace(/\\s+/g, '');
                        if (!t || t.length > 12) return false;
                        return keywords.some(k => t.includes(k));
                      });
                      let target = pick(nodes);
                      if (!target) {
                        target = nodes.find(el => {
                          if (!present(el)) return false;
                          const t = (el.innerText || el.textContent || '').replace(/\\s+/g, '');
                          if (!t || t.length > 8) return false;
                          if (t.includes('搜索') || t.includes('登录')) return false;
                          return soft.some(k => t === k || t.startsWith(k));
                        });
                      }
                      if (!target) return null;
                      target.scrollIntoView({ behavior: 'instant', block: 'center' });
                      target.click();
                      return (target.innerText || target.textContent || '').trim();
                    })()
                    """);
                if (clicked instanceof String && !((String) clicked).isBlank()) {
                    log.info("[51job] JS 兜底已点击投递按钮: {}", clicked);
                    sendProgress("已点击批量投递按钮", null, null);
                    for (int i = 0; i < 10; i++) {
                        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                        if (detectDailyLimitToast51job()) {
                            reachedDailyLimit = true;
                            log.warn("点击投递按钮后，检测到 51job 日投递上限提示，停止投递");
                            sendProgress("检测到日投递上限，任务已停止", null, null);
                            return true;
                        }
                    }
                    return true;
                }
            } catch (Exception e) {
                log.debug("[51job] JS 兜底点击失败: {}", e.getMessage());
            }

            // 兼容旧选择器：tabs_in 下第 2 个 p_but
            try {
                Locator parent = page.locator("div.tabs_in");
                Locator buttons = parent.locator("button.p_but");
                if (buttons.count() > 1) {
                    Locator target = buttons.nth(1);
                    String text = "";
                    try { text = target.innerText(); } catch (Exception ignored) {}
                    try { target.scrollIntoViewIfNeeded(); } catch (Exception ignored) {}
                    target.click(new Locator.ClickOptions().setForce(true).setTimeout(3000));
                    log.info("[51job] 旧选择器已点击批量投递: text={}", text == null ? "" : text.trim());
                    return true;
                }
            } catch (Exception ignored) {}

            log.warn("[51job] 第 {} 次未找到批量投递按钮，重试…", retry + 1);
            PlaywrightUtil.sleep(1);
        }
        return false;
    }

    private void dumpVisibleActionButtons() {
        try {
            Object info = page.evaluate("""
                (() => {
                  const nodes = Array.from(document.querySelectorAll('button, a.el-button, [class*="p_but"], [class*="apply"]'));
                  return nodes.slice(0, 30).map(el => {
                    const t = (el.innerText || '').replace(/\\s+/g, ' ').trim();
                    return {tag: el.tagName, class: el.className, text: t.slice(0, 40)};
                  }).filter(x => x.text);
                })()
                """);
            log.warn("[51job] 页面可见操作按钮快照: {}", info);
        } catch (Exception e) {
            log.debug("[51job] 导出按钮快照失败: {}", e.getMessage());
        }
    }

    /**
     * 处理批量投递后的唯一结果弹窗：解析结果 → 标记已投递 → 关闭弹窗。
     * 批量投递通常只有一个弹窗，不依赖「投递成功N个」文案才能更新状态。
     */
    private void handleDeliverySuccessDialog(List<Long> selectedIds) {
        String dialogText = null;
        boolean sawDialog = false;
        try {
            // 弹窗可能延迟出现，短暂轮询
            for (int i = 0; i < 12; i++) {
                if (shouldStop()) {
                    return;
                }
                dialogText = readDeliveryDialogText();
                if (dialogText != null && !dialogText.isBlank()) {
                    sawDialog = true;
                    break;
                }
                if (hasVisibleDeliveryDialog()) {
                    sawDialog = true;
                    dialogText = readDeliveryDialogText();
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

            Integer successNum = null;
            Integer failNum = null;
            if (dialogText != null && !dialogText.isBlank()) {
                try {
                    java.util.regex.Matcher m1 = java.util.regex.Pattern
                            .compile("投递成功\\D*(\\d+)").matcher(dialogText);
                    if (m1.find()) {
                        successNum = Integer.parseInt(m1.group(1));
                    }
                    java.util.regex.Matcher m2 = java.util.regex.Pattern
                            .compile("未投递\\D*(\\d+)").matcher(dialogText);
                    if (m2.find()) {
                        failNum = Integer.parseInt(m2.group(1));
                    }
                } catch (Exception ignored) {}
                log.info("[51job] 投递结果弹窗：成功 {} 个，未投递 {} 个 | text={}",
                        successNum, failNum,
                        dialogText.length() > 80 ? dialogText.substring(0, 80) + "…" : dialogText);
                if (successNum != null || failNum != null
                        || dialogText.contains("投递成功") || dialogText.contains("投递完成")
                        || dialogText.contains("申请成功")) {
                    sendProgress(String.format("投递结果：成功 %s 个，未投递 %s 个",
                            successNum == null ? "?" : successNum,
                            failNum == null ? "?" : failNum), null, null);
                }
            }

            // 批量投递成功后：按勾选岗位更新已投递（弹窗只有一个，文案解析失败也要更新）
            markSelectedJobsDelivered(selectedIds, successNum, true);

            dismissDeliveryDialogs(3);

            try {
                if (detectDailyLimitToast51job()) {
                    reachedDailyLimit = true;
                    log.warn("处理成功弹窗后，检测到 51job 日投递上限提示，停止当前页");
                }
            } catch (Exception ignored) {}
        } catch (Exception e) {
            log.debug("处理投递结果弹窗失败: {}", e.getMessage());
            markSelectedJobsDelivered(selectedIds, null, true);
            dismissDeliveryDialogs(3);
        }
    }

    private String readDeliveryDialogText() {
        String[] selectors = {
                ".el-dialog__body",
                ".el-message-box__message",
                ".el-message-box__content",
                ".el-dialog",
                ".successContent"
        };
        for (String sel : selectors) {
            try {
                Locator loc = page.locator(sel);
                if (loc.count() == 0) {
                    continue;
                }
                String text = loc.first().innerText();
                if (text != null && !text.isBlank()) {
                    return text.trim();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void markSelectedJobsDelivered(List<Long> selectedIds, Integer successNum, boolean deliveredLikely) {
        if (selectedIds == null || selectedIds.isEmpty()) {
            log.warn("[51job] 本轮无可标记的 jobId（勾选列表为空），无法更新投递状态");
            return;
        }
        if (!deliveredLikely && (successNum == null || successNum <= 0)) {
            log.warn("[51job] 未检测到投递结果，暂不标记已投递");
            return;
        }
        try {
            List<Long> toMark = selectedIds;
            if (successNum != null && successNum > 0 && successNum < selectedIds.size()) {
                toMark = selectedIds.subList(0, successNum);
            }
            job51Service.markDeliveredBatch(toMark);
            log.info("[51job] 已更新投递状态：标记 {} 个为已投递", toMark.size());
            sendProgress(String.format("已更新 %d 个岗位为已投递", toMark.size()), null, null);
        } catch (Exception e) {
            log.warn("[51job] 标记已投递失败: {}", e.getMessage());
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
     * 翻到下一页。优先点「下一页」，失败再尝试页码输入跳转。
     * 必须确认列表/jobId 相对翻页前发生变化，否则视为翻页失败（避免旧 DOM 空转投递、统计不落库）。
     */
    private boolean goToNextPage() {
        dismissDeliveryDialogs(2);
        List<Long> beforeIds = snapshotCurrentPageJobIds();
        resetSearchApiFuture();
        clearPageJobCache();

        // 1) 下一页按钮
        String[] nextSelectors = {
                "button:has-text(\"下一页\")",
                "a:has-text(\"下一页\")",
                "li.btn-next:not(.disabled)",
                "button.btn-next:not(.disabled)",
                ".el-pagination .btn-next:not(.disabled)",
                ".el-pager + button.btn-next",
                "span.next:not(.disabled)",
                "a.next:not(.disabled)",
                "[class*='next']:not([class*='disabled']):has-text(\"下一页\")",
                "[aria-label='下一页']",
                "[aria-label='Next']"
        };
        for (String sel : nextSelectors) {
            try {
                Locator next = page.locator(sel);
                if (next.count() == 0) {
                    continue;
                }
                Locator first = next.first();
                String cls = "";
                try { cls = first.getAttribute("class"); } catch (Exception ignored) {}
                if (cls != null && (cls.contains("disabled") || cls.contains("is-disabled"))) {
                    log.info("[51job] 下一页按钮不可用: {}", sel);
                    return false;
                }
                if (!first.isVisible()) {
                    continue;
                }
                first.scrollIntoViewIfNeeded();
                first.click(new Locator.ClickOptions().setTimeout(3000));
                log.info("[51job] 已点击下一页: {}", sel);
                return waitAfterPageChange(beforeIds);
            } catch (Exception ignored) {}
        }

        // 2) 通过页码输入框跳到下一页
        try {
            Locator pageInput = page.locator("#jump_page, input.jumpPage, .jumpPage input, input[type='number']");
            if (pageInput.count() > 0 && pageInput.first().isVisible()) {
                int target = currentPageNum + 1;
                Locator input = pageInput.first();
                input.click();
                input.fill(String.valueOf(target));
                Locator jumpBtn = page.locator("span.jumpPage, button:has-text('确定'), .jump-btn");
                if (jumpBtn.count() > 0) {
                    jumpBtn.first().click();
                } else {
                    input.press("Enter");
                }
                log.info("[51job] 通过页码输入跳到第{}页", target);
                return waitAfterPageChange(beforeIds);
            }
        } catch (Exception e) {
            log.debug("[51job] 页码跳转失败: {}", e.getMessage());
        }

        // 3) JS 兜底找下一页
        try {
            Object clicked = page.evaluate("""
                (() => {
                  const candidates = Array.from(document.querySelectorAll('button,a,li,span'));
                  const next = candidates.find(el => {
                    const t = (el.innerText || '').replace(/\\s+/g, '');
                    const cls = (el.className || '').toString();
                    if (cls.includes('disabled') || cls.includes('is-disabled')) return false;
                    return t === '下一页' || t === '下页' || t === '>';
                  });
                  if (!next) return false;
                  next.click();
                  return true;
                })()
                """);
            if (Boolean.TRUE.equals(clicked)) {
                log.info("[51job] JS 兜底已点击下一页");
                return waitAfterPageChange(beforeIds);
            }
        } catch (Exception ignored) {}

        log.warn("[51job] 未找到可用的下一页控件");
        return false;
    }

    private void clearPageJobCache() {
        synchronized (currentPageJobIds) {
            currentPageJobIds.clear();
        }
        synchronized (currentPageCompanyByJobId) {
            currentPageCompanyByJobId.clear();
        }
    }

    private List<Long> snapshotCurrentPageJobIds() {
        synchronized (currentPageJobIds) {
            if (!currentPageJobIds.isEmpty()) {
                return new ArrayList<>(currentPageJobIds);
            }
        }
        return collectJobIdsOnPage();
    }

    private boolean waitAfterPageChange(List<Long> beforeIds) {
        PlaywrightUtil.sleep(2);
        CompletableFuture<Integer> apiFuture = searchApiFuture;
        if (apiFuture != null) {
            long deadline = System.currentTimeMillis() + 12_000;
            while (System.currentTimeMillis() < deadline) {
                if (shouldStop()) {
                    return false;
                }
                if (apiFuture.isDone()) {
                    try {
                        Integer count = apiFuture.get(0, TimeUnit.SECONDS);
                        lastSearchApiJobCount = count != null ? count : 0;
                        log.info("[51job] 翻页后搜索接口返回 {} 个岗位", lastSearchApiJobCount);
                    } catch (Exception ignored) {}
                    break;
                }
                PlaywrightUtil.sleepMillis(200);
            }
            if (!apiFuture.isDone()) {
                log.warn("[51job] 翻页后等待搜索接口超时");
            }
        }
        // 注意：不要用 div.ick —— 勾选框常隐藏，waitForSelector(visible) 会误超时
        try {
            page.waitForSelector(
                    "[class*='jname'], a[href*='jobdetail'], a[href*='jobs.51job.com/']",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(8000)
            );
        } catch (Exception e) {
            log.warn("[51job] 翻页后岗位列表未就绪: {}", e.getMessage());
            if (findJobCheckboxes().count() == 0) {
                return false;
            }
        }

        List<Long> afterIds = snapshotCurrentPageJobIds();
        if (afterIds == null || afterIds.isEmpty()) {
            log.warn("[51job] 翻页后未拿到岗位 ID，结束翻页");
            return false;
        }
        if (beforeIds != null && !beforeIds.isEmpty() && beforeIds.equals(afterIds)) {
            log.warn("[51job] 翻页后岗位 ID 未变化（仍为旧列表），结束翻页以免空转");
            return false;
        }
        // 确保缓存与当前页一致（API 未回填时用 DOM）
        synchronized (currentPageJobIds) {
            if (currentPageJobIds.isEmpty()) {
                currentPageJobIds.addAll(afterIds);
            }
        }
        return true;
    }

    /**
     * @deprecated 保留旧跳页方法供排查，实际流程改用 {@link #goToNextPage()}
     */
    private boolean jumpToPage(int pageNum) {
        for (int retry = 0; retry < 3; retry++) {
            try {
                if (shouldStop()) {
                    return false;
                }
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
                Locator jumpButton = page.locator("span.jumpPage, .jumpPage");
                if (jumpButton.count() > 0) {
                    jumpButton.first().click();
                }
                page.evaluate("window.scrollTo(0, 0)");
                PlaywrightUtil.sleep(2);
                log.info("成功跳转到第{}页", pageNum);
                return true;
            } catch (Exception e) {
                log.warn("跳转到第{}页失败，重试第{}次: {}", pageNum, retry + 1, e.getMessage());
                PlaywrightUtil.sleep(1);
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
        query.append(JobUtils.appendParam("workYear", config.getWorkYear()));
        query.append(JobUtils.appendParam("degree", config.getDegree()));
        query.append(JobUtils.appendParam("companyType", config.getCompanyType()));
        query.append(JobUtils.appendParam("companySize", config.getCompanySize()));
        query.append(JobUtils.appendParam("jobType", config.getJobType()));
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
     * 优先用 API 缓存的公司名；缺失时再回退 DOM（仍可能因下标错位不准）。
     */
    private String resolveJob51CompanyName(Long jobId, int index, Locator companies, Map<Long, String> companyByJobId) {
        if (jobId != null && companyByJobId != null) {
            String fromApi = companyByJobId.get(jobId);
            if (!BlacklistService.isPlaceholderCompany(fromApi)) {
                return fromApi.trim();
            }
        }
        try {
            if (companies != null && index < companies.count()) {
                String raw = companies.nth(index).textContent();
                if (raw != null && !raw.isBlank()) {
                    return raw.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 从搜索 JSON 缓存当前页 jobId 与公司名。
     */
    private void cachePageJobsFromJson(String json) {
        List<Long> jobIds = new ArrayList<>();
        Map<Long, String> companies = new HashMap<>();
        if (json == null || json.trim().isEmpty()) {
            return;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);

            com.fasterxml.jackson.databind.JsonNode list = root.path("data").path("items");
            if (!list.isArray()) list = root.path("data").path("jobList");
            if (!list.isArray()) list = root.path("data").path("list");
            if (!list.isArray()) list = root.path("data").path("jobs");
            if (!list.isArray()) list = root.path("resultbody").path("job").path("items");
            if (!list.isArray()) list = root.path("job").path("items");
            if (!list.isArray()) list = root.path("resultbody").path("items");
            if (!list.isArray()) {
                return;
            }

            for (com.fasterxml.jackson.databind.JsonNode item : list) {
                Long jobId = readJob51JobId(item);
                if (jobId == null) {
                    continue;
                }
                jobIds.add(jobId);
                String company = readJob51CompanyName(item);
                if (!BlacklistService.isPlaceholderCompany(company)) {
                    companies.put(jobId, company.trim());
                }
            }
        } catch (Exception e) {
            log.warn("[51job] 解析JSON提取jobId/公司名失败: {}", e.getMessage());
            return;
        }

        if (!jobIds.isEmpty()) {
            synchronized (currentPageJobIds) {
                currentPageJobIds.clear();
                currentPageJobIds.addAll(jobIds);
            }
            synchronized (currentPageCompanyByJobId) {
                currentPageCompanyByJobId.clear();
                currentPageCompanyByJobId.putAll(companies);
            }
        }
    }

    private static Long readJob51JobId(com.fasterxml.jackson.databind.JsonNode item) {
        com.fasterxml.jackson.databind.JsonNode jobIdNode = item.path("jobId");
        if (jobIdNode.isMissingNode() || jobIdNode.isNull()) {
            return null;
        }
        try {
            long jobId = jobIdNode.asLong();
            return jobId > 0 ? jobId : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readJob51CompanyName(com.fasterxml.jackson.databind.JsonNode item) {
        for (String key : new String[]{"fullCompanyName", "companyName", "ctmName"}) {
            try {
                String v = item.path(key).asText(null);
                if (v != null && !v.isBlank() && !"null".equals(v)) {
                    return v;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }


}
