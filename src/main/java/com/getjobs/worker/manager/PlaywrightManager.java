package com.getjobs.worker.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.entity.CookieEntity;
import com.getjobs.application.service.CookieService;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import com.microsoft.playwright.options.LoadState;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Playwright管理器
 * Spring管理的单例Bean，在应用启动时自动初始化Playwright实例
 * 支持4个求职平台的共享BrowserContext和登录状态监控
 * 所有平台在同一个浏览器窗口的不同标签页中运行
 */
@Slf4j
@Getter
@Component
public class PlaywrightManager {

    // Playwright实例
    private Playwright playwright;

    // 浏览器实例（所有平台共享）
    private Browser browser;

    // 浏览器上下文（所有平台共享，在同一个窗口中打开多个标签页）
    private BrowserContext context;

    // Boss直聘页面
    private Page bossPage;

    // 猎聘页面
    private Page liepinPage;

    // 51job页面（预留）
    private Page job51Page;

    // 智联招聘页面（预留）
    private Page zhilianPage;

    // 启动时展示的管理页面（本地前端）
    private Page managementPage;

    // 登录状态追踪（平台 -> 是否已登录）
    private final Map<String, Boolean> loginStatus = new ConcurrentHashMap<>();

    // 登录状态监听器
    private final List<Consumer<LoginStatusChange>> loginStatusListeners = new CopyOnWriteArrayList<>();

    // 控制是否暂停对bossPage的后台监控，避免与任务执行并发访问同一页面
    private volatile boolean bossMonitoringPaused = false;
    // 控制是否暂停对liepinPage的后台监控
    private volatile boolean liepinMonitoringPaused = false;

    // 控制是否暂停对51jobPage的后台监控
  private volatile boolean job51MonitoringPaused = false;

    // 控制是否暂停对zhilianPage的后台监控
    private volatile boolean zhilianMonitoringPaused = false;

    // 记录智联招聘是否已处理过未登录引导（仅初始化时执行一次）
    private volatile boolean zhilianLoginGuided = false;
    // 记录 Boss 是否已引导过登录页（避免反复 navigate 导致登录页一直刷新）
    private volatile boolean bossLoginGuided = false;

    // 默认超时时间（毫秒）
  private static final int DEFAULT_TIMEOUT = 30000;

    // Playwright调试端口
    private static final int CDP_PORT = 7866;

    // 平台URL常量
    private static final String BOSS_URL = "https://www.zhipin.com";
    private static final String LIEPIN_URL = "https://www.liepin.com";
  private static final String JOB51_URL = "https://www.51job.com";
    private static final String ZHILIAN_URL = "https://www.zhaopin.com";
    private static final String BOSS_DOMAIN = "zhipin.com";
    private static final String LIEPIN_DOMAIN = "liepin.com";
    private static final String JOB51_DOMAIN = "51job.com";
    private static final String ZHILIAN_DOMAIN = "zhaopin.com";
    private static final String BOSS_INIT_SCRIPT_RESOURCE = "anti-detection.js";
    private static final Set<String> SUPPORTED_PLATFORMS = Set.of("boss", "liepin", "51job", "zhilian");
    // 51job 登录等待线程（避免重复启动多个轮询线程）
    private volatile boolean wait51jobLoginThreadRunning = false;
    // 降噪：51job Cookie保存日志节流状态
    private volatile long last51CookieLogMs = 0L;
    private volatile int last51CookieLogCount = -1;
    private volatile String last51CookieRemark = "";

    /** 单平台模式：同一时刻仅保持一个平台 Page 活跃 */
    private final Object platformLock = new Object();
    private volatile String activePlatform = null;

    @Autowired
    private CookieService cookieService;

    /**
     * 确保 Playwright 浏览器引擎已就绪（不创建任何平台 Page）
     */
    private void ensureBrowserReady() {
        if (browser != null && context != null) {
            return;
        }
        log.info("========================================");
        log.info("  启动浏览器自动化引擎（按需单平台模式）");
        log.info("========================================");

        try {
            playwright = Playwright.create();
            log.info("✓ Playwright引擎已启动");

            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(false)
                    .setIgnoreDefaultArgs(List.of("--enable-automation"))
                    .setArgs(List.of(
                            "--remote-debugging-port=" + CDP_PORT,
                            "--start-maximized",
                            "--disable-blink-features=AutomationControlled",
                            "--disable-infobars"
                    ));

            try {
                // 优先使用本机 Chrome，降低 Playwright 自带 Chromium 被风控识别的概率
                browser = playwright.chromium().launch(launchOptions.setChannel("chrome"));
                log.info("✓ 本机 Chrome 已启动 (调试端口: {})", CDP_PORT);
            } catch (Exception chromeEx) {
                log.warn("本机 Chrome 不可用，回退 Playwright Chromium: {}", chromeEx.getMessage());
                browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                        .setHeadless(false)
                        .setIgnoreDefaultArgs(List.of("--enable-automation"))
                        .setArgs(List.of(
                                "--remote-debugging-port=" + CDP_PORT,
                                "--start-maximized",
                                "--disable-blink-features=AutomationControlled",
                                "--disable-infobars"
                        )));
                log.info("✓ Playwright Chromium 已启动 (调试端口: {})", CDP_PORT);
            }

            context = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(null)
                    .setLocale("zh-CN")
                    .setTimezoneId("Asia/Shanghai")
                    .setUserAgent(
                            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"));
            log.info("✓ BrowserContext已创建");
            injectBossInitScript(context);
            log.info("✓ 浏览器引擎就绪，等待用户选择平台后打开对应标签页");
            log.info("========================================");
        } catch (Exception e) {
            log.error("✗ 浏览器自动化引擎启动失败", e);
            throw new RuntimeException("Playwright启动失败", e);
        }
    }

    /**
     * 打开本地管理页面（启动时调用，使用 Playwright Chrome）
     */
    public void openManagementPage(String url) {
        synchronized (platformLock) {
            ensureBrowserReady();

            if (managementPage != null && !managementPage.isClosed()) {
                try {
                    String current = managementPage.url();
                    // 同 origin 的 SPA 路由切换不要用 navigate 全量刷新，否则会反复 remount 并触发 openPlatform
                    if (current != null && isSameHttpOrigin(current, url)) {
                        bringPageToFront(managementPage);
                        log.debug("管理页面已在同 origin，跳过 navigate: current={}, requested={}", current, url);
                        return;
                    }
                    if (current == null || !current.startsWith(stripTrailingSlash(url))) {
                        managementPage.navigate(url, new Page.NavigateOptions()
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                                .setTimeout(60_000));
                    }
                    bringPageToFront(managementPage);
                    log.info("✓ 管理页面已存在，已切换到前台: {}", url);
                    return;
                } catch (Exception e) {
                    log.debug("复用管理页面失败，将重新创建: {}", e.getMessage());
                    closeManagementPageQuietly();
                }
            }

            managementPage = context.newPage();
            managementPage.setDefaultTimeout(DEFAULT_TIMEOUT);
            managementPage.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(60_000));
            bringPageToFront(managementPage);
            log.info("✓ 管理页面已打开: {}", url);
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        return url.replaceAll("/$", "");
    }

    private static boolean isSameHttpOrigin(String a, String b) {
        try {
            URI ua = URI.create(a);
            URI ub = URI.create(b);
            String ha = ua.getHost() == null ? "" : ua.getHost();
            String hb = ub.getHost() == null ? "" : ub.getHost();
            int pa = ua.getPort() == -1 ? defaultPort(ua.getScheme()) : ua.getPort();
            int pb = ub.getPort() == -1 ? defaultPort(ub.getScheme()) : ub.getPort();
            return Objects.equals(ua.getScheme(), ub.getScheme())
                    && ha.equalsIgnoreCase(hb)
                    && pa == pb;
        } catch (Exception e) {
            return false;
        }
    }

    private static int defaultPort(String scheme) {
        if ("https".equalsIgnoreCase(scheme)) {
            return 443;
        }
        if ("http".equalsIgnoreCase(scheme)) {
            return 80;
        }
        return -1;
    }

    private void closeManagementPageQuietly() {
        if (managementPage == null) {
            return;
        }
        try {
            if (!managementPage.isClosed()) {
                managementPage.close();
                log.info("已关闭管理页面标签页");
            }
        } catch (Exception e) {
            log.debug("关闭管理页面标签页时异常: {}", e.getMessage());
        } finally {
            managementPage = null;
        }
    }

    private void bringPageToFront(Page page) {
        if (page == null) {
            return;
        }
        try {
            page.bringToFront();
        } catch (Exception ignored) {
        }
    }

    /**
     * 打开指定平台（单平台模式：切换时关闭其他招聘站点标签页，保留管理前台）
     */
    public Map<String, Object> openPlatform(String platform) {
        return openPlatform(platform, true, false);
    }

    /**
     * @param focus true 时将招聘站点标签页置于前台；false 时保持管理页在前台（同一 CDP 浏览器内预开平台）
     */
    public Map<String, Object> openPlatform(String platform, boolean focus) {
        return openPlatform(platform, focus, false);
    }

    /**
     * @param focus true 时将招聘站点标签页置于前台
     * @param forceLogin true 时强制拉回 Boss 登录页（仅「打开登录页」按钮使用，避免状态轮询反复 navigate）
     */
    public Map<String, Object> openPlatform(String platform, boolean focus, boolean forceLogin) {
        String normalized = normalizePlatform(platform);
        synchronized (platformLock) {
            ensureBrowserReady();

            if (!normalized.equals(activePlatform)) {
                closeOtherPlatformPages(normalized);
                activePlatform = normalized;
            }

            Page platformPage = switch (normalized) {
                case "boss" -> ensureBossPageReady();
                case "liepin" -> ensureLiepinPageReady();
                case "51job" -> ensure51jobPageReady();
                case "zhilian" -> ensureZhilianPageReady();
                default -> throw new IllegalArgumentException("不支持的平台: " + platform);
            };

            if (focus) {
                bringPageToFront(platformPage);
            } else if (managementPage != null && !managementPage.isClosed()) {
                bringPageToFront(managementPage);
            } else {
                bringPageToFront(platformPage);
            }

            boolean loggedIn = refreshPlatformLoginStatus(normalized);
            if (!loggedIn && "boss".equals(normalized) && !bossMonitoringPaused) {
                if (forceLogin) {
                    // 用户主动点「打开登录页」：允许再次引导
                    bossLoginGuided = false;
                    ensureBossLoginPageVisible(true);
                } else {
                    // 常规 open（路由同步 / 投递前）：整个 Page 只引导一次，避免登录成功或投递页被反复刷新
                    guideBossLoginOnce();
                }
            }

            // forceLogin 可能改变 URL，再读一次状态
            if (forceLogin && "boss".equals(normalized)) {
                loggedIn = refreshPlatformLoginStatus(normalized);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("platform", normalized);
            result.put("activePlatform", activePlatform);
            result.put("isLoggedIn", loggedIn);
            result.put("browserReady", true);
            result.put("focused", focus);
            result.put("managementPageOpen", managementPage != null && !managementPage.isClosed());
            result.put("message", "已打开 " + platformLabel(normalized));
            return result;
        }
    }

    public String getActivePlatform() {
        return activePlatform;
    }

    private String normalizePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("平台不能为空");
        }
        String normalized = platform.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_PLATFORMS.contains(normalized)) {
            throw new IllegalArgumentException("不支持的平台: " + platform);
        }
        return normalized;
    }

    private String platformLabel(String platform) {
        return switch (platform) {
            case "boss" -> "Boss直聘";
            case "liepin" -> "猎聘";
            case "51job" -> "51job";
            case "zhilian" -> "智联招聘";
            default -> platform;
        };
    }

    private void closeOtherPlatformPages(String keepPlatform) {
        if (!"boss".equals(keepPlatform)) {
            closePageQuietly("boss", bossPage);
            bossPage = null;
            bossMonitoringPaused = false;
            bossLoginGuided = false;
        }
        if (!"liepin".equals(keepPlatform)) {
            closePageQuietly("liepin", liepinPage);
            liepinPage = null;
            liepinMonitoringPaused = false;
        }
        if (!"51job".equals(keepPlatform)) {
            closePageQuietly("51job", job51Page);
            job51Page = null;
            job51MonitoringPaused = false;
        }
        if (!"zhilian".equals(keepPlatform)) {
            closePageQuietly("zhilian", zhilianPage);
            zhilianPage = null;
            zhilianMonitoringPaused = false;
            zhilianLoginGuided = false;
        }
    }

    private void closePageQuietly(String platform, Page page) {
        if (page == null) {
            return;
        }
        try {
            if (!page.isClosed()) {
                page.close();
                log.info("已关闭 {} 平台标签页", platformLabel(platform));
            }
        } catch (Exception e) {
            log.debug("关闭 {} 标签页时异常: {}", platform, e.getMessage());
        }
    }

    private Page ensureBossPageReady() {
        if (bossPage != null && !bossPage.isClosed()) {
            log.info("Boss Page 已存在，跳过重复初始化");
            return bossPage;
        }
        bossPage = context.newPage();
        bossPage.setDefaultTimeout(DEFAULT_TIMEOUT);
        log.info("✓ Boss Page已创建");
        setupBossPlatform();
        return bossPage;
    }

    private Page ensureLiepinPageReady() {
        if (liepinPage != null && !liepinPage.isClosed()) {
            log.info("猎聘 Page 已存在，跳过重复初始化");
            return liepinPage;
        }
        liepinPage = context.newPage();
        liepinPage.setDefaultTimeout(DEFAULT_TIMEOUT);
        log.info("✓ 猎聘 Page已创建");
        setupLiepinPlatform();
        return liepinPage;
    }

    private Page ensure51jobPageReady() {
        if (job51Page != null && !job51Page.isClosed()) {
            log.info("51job Page 已存在，跳过重复初始化");
            return job51Page;
        }
        job51Page = context.newPage();
        job51Page.setDefaultTimeout(DEFAULT_TIMEOUT);
        log.info("✓ 51job Page已创建");
        setup51jobPlatform();
        return job51Page;
    }

    private Page ensureZhilianPageReady() {
        if (zhilianPage != null && !zhilianPage.isClosed()) {
            log.info("智联招聘 Page 已存在，跳过重复初始化");
            return zhilianPage;
        }
        zhilianPage = context.newPage();
        zhilianPage.setDefaultTimeout(DEFAULT_TIMEOUT);
        log.info("✓ 智联招聘 Page已创建");
        setupZhilianPlatform();
        return zhilianPage;
    }

    private boolean refreshPlatformLoginStatus(String platform) {
        boolean loggedIn = switch (platform) {
            case "boss" -> bossPage != null && checkIfLoggedIn();
            case "liepin" -> liepinPage != null && checkIfLiepinLoggedIn();
            case "51job" -> job51Page != null && checkIf51jobLoggedIn();
            case "zhilian" -> zhilianPage != null && checkIfZhilianLoggedIn();
            default -> false;
        };
        setLoginStatus(platform, loggedIn);
        return loggedIn;
    }

    /**
     * @deprecated 请使用 {@link #openPlatform(String)} 按需打开单个平台
     */
    @Deprecated
    public void init() {
        ensureBrowserReady();
    }

    /**
     * 在上下文层统一注入 Boss 脚本，仅对 zhipin.com 生效。
     */
    private void injectBossInitScript(BrowserContext targetContext) {
        String script = readResourceText(BOSS_INIT_SCRIPT_RESOURCE);
        if (script == null || script.isBlank()) {
            log.warn("Boss 反检测脚本未加载，资源不存在或为空: {}", BOSS_INIT_SCRIPT_RESOURCE);
            return;
        }
        String wrapped = "(function(){try{if(location&&location.origin===\"https://www.zhipin.com\"){"
                + "if(window.__bossAntiDetectInjected){return;}window.__bossAntiDetectInjected=true;"
                + script + "}}catch(e){}})();";
        targetContext.addInitScript(wrapped);
        log.info("Boss 反检测脚本已注入到Context: {}", BOSS_INIT_SCRIPT_RESOURCE);
    }

    private String readResourceText(String resourcePath) {
        try (InputStream input = PlaywrightManager.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                return null;
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取资源失败: {} - {}", resourcePath, e.getMessage());
            return null;
        }
    }

    /**
     * 设置Boss直聘平台（加载Cookie、导航、监控）
     */
    private void setupBossPlatform() {
        log.info("开始初始化Boss直聘平台...");
        // 有 Cookie 时先打开首页验证登录态；无 Cookie / 未登录则直接进登录页，避免先落主页再跳走
        boolean hasCookie = false;
        try {
            CookieEntity cookieEntity = cookieService.getCookieByPlatform("boss");
            hasCookie = cookieEntity != null
                    && cookieEntity.getCookieValue() != null
                    && !cookieEntity.getCookieValue().isBlank();
            if (hasCookie) {
                String cookieStr = cookieEntity.getCookieValue();
                List<Cookie> cookies = filterCookiesByDomain(parseCookiesFromString(cookieStr), BOSS_DOMAIN);

                if (!cookies.isEmpty()) {
                    context.addCookies(cookies);
                    log.info("已从数据库加载Boss Cookie并注入浏览器上下文，共 {} 条", cookies.size());
                } else {
                    log.warn("解析Cookie失败，未能加载任何Cookie");
                    hasCookie = false;
                }
            } else {
                log.info("数据库未找到Boss Cookie或值为空，跳过Cookie注入");
            }
        } catch (Exception e) {
            log.warn("从数据库加载Boss Cookie失败: {}", e.getMessage());
            hasCookie = false;
        }

        if (hasCookie) {
            navigateBossWithRetry(BOSS_URL);
            try {
                bossPage.waitForLoadState(LoadState.NETWORKIDLE);
            } catch (Exception e) {
                log.debug("等待Boss页面网络空闲失败: {}", e.getMessage());
            }
            setLoginStatus("boss", checkIfLoggedIn());
            if (!isLoggedIn("boss")) {
                ensureBossLoginPageVisible(true);
            }
        } else {
            setLoginStatus("boss", false);
            ensureBossLoginPageVisible(true);
        }
        // 设置登录状态监控
        setupLoginMonitoring(bossPage);
    }

    private void navigateBossWithRetry(String url) {
        int maxRetries = 3;
        boolean navigateSuccess = false;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                bossPage.navigate(url, new Page.NavigateOptions()
                        .setTimeout(60000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                navigateSuccess = true;
                break;
            } catch (Exception e) {
                boolean pageAccessible = false;
                try {
                    String current = bossPage.url();
                    pageAccessible = current != null && current.contains("zhipin.com");
                } catch (Exception ignored) {
                }
                if (pageAccessible) {
                    navigateSuccess = true;
                    break;
                }
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        if (!navigateSuccess) {
            log.warn("Boss直聘页面导航失败: {}", url);
        }
    }

    /**
     * 确保当前停留在 Boss 登录页（扫码引导）。
     * @param forceQrSwitch 是否尝试切换到二维码登录（初始化时 true；仅纠偏回登录页时可 false）
     */
    private void ensureBossLoginPageVisible(boolean forceQrSwitch) {
        if (bossPage == null || bossPage.isClosed()) {
            return;
        }
        try {
            String currentUrl = safePageUrl(bossPage);

            // 已进入求职端工作区：视为登录成功，禁止再 navigate 登录页（否则登录后/投递页会一直刷新）
            if (isBossLoggedInWorkspaceUrl(currentUrl)) {
                bossLoginGuided = true;
                log.debug("Boss 已在登录工作区，跳过登录页引导: {}", currentUrl);
                return;
            }
            // 安全校验页：不要再跳登录页或其它地址，否则会加剧 security 重定向嵌套
            if (isBossSecurityUrl(currentUrl)) {
                log.warn("Boss 当前处于安全校验页，跳过登录引导: {}", summarizeBossUrl(currentUrl));
                return;
            }

            boolean alreadyOnLogin = isBossLoginUrl(currentUrl);
            if (!alreadyOnLogin) {
                log.info("Boss 当前不在登录页（{}），导航到登录页", currentUrl);
                navigateBossWithRetry(BOSS_URL + "/web/user/?ka=header-login");
                try {
                    Thread.sleep(800);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                bossLoginGuided = true;
                forceQrSwitch = true;
            } else if (!bossLoginGuided) {
                bossLoginGuided = true;
            }

            if (forceQrSwitch) {
                trySwitchBossQrLogin();
            }
        } catch (Exception e) {
            log.debug("确保 Boss 登录页可见失败: {}", e.getMessage());
        }
    }

    private void trySwitchBossQrLogin() {
        try {
            Locator qrPane = bossPage.locator(".qr-code-box, .qrcode-img, img.qr-code-img, canvas.login-qrcode").first();
            if (qrPane.count() > 0 && qrPane.isVisible()) {
                log.info("Boss 已在扫码登录态，跳过切换按钮");
                return;
            }

            Locator qrSwitch = bossPage.locator(".btn-sign-switch.ewm-switch").first();
            if (qrSwitch.count() > 0 && qrSwitch.isVisible()) {
                qrSwitch.click();
                log.info("已切换 Boss 二维码登录");
                return;
            }
            Locator tip = bossPage.getByText("APP扫码登录").first();
            if (tip.count() > 0 && tip.isVisible()) {
                tip.click();
                log.info("已点击包含文本的二维码登录切换提示（APP扫码登录）");
                return;
            }
            Locator legacy = bossPage.locator("li.sign-switch-tip").first();
            if (legacy.count() > 0 && legacy.isVisible()) {
                legacy.click();
                log.info("已通过旧版选择器切换二维码登录（li.sign-switch-tip）");
                return;
            }
            log.info("未找到二维码登录切换按钮，保持当前登录页");
        } catch (Exception e) {
            log.debug("切换二维码登录失败: {}", e.getMessage());
        }
    }

    /**
     * 整个 Boss Page 生命周期仅自动引导一次登录页，避免 openPlatform / 路由同步反复刷新。
     */
    private void guideBossLoginOnce() {
        if (bossLoginGuided || bossPage == null || bossPage.isClosed()) {
            return;
        }
        ensureBossLoginPageVisible(true);
    }

    private static String safePageUrl(Page page) {
        try {
            return page == null ? null : page.url();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBossLoginUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        // 只认明确登录入口，避免宽泛匹配 "/login" 子串误伤业务页
        return url.contains("/web/user/")
                || url.contains("ka=header-login");
    }

    private static boolean isBossSecurityUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        return url.contains("/web/passport/zp/security")
                || url.contains("security-check")
                || url.contains("_security_check")
                || url.contains("/web/common/security-check");
    }

    /** 日志里避免把嵌套 srcReferer 整段打出来 */
    private static String summarizeBossUrl(String url) {
        if (url == null) {
            return null;
        }
        if (url.length() <= 160) {
            return url;
        }
        return url.substring(0, 160) + "...(len=" + url.length() + ")";
    }

    private static boolean isBossLoggedInWorkspaceUrl(String url) {
        if (url == null) {
            return false;
        }
        return url.contains("/web/geek/")
                || url.contains("/web/chat")
                || url.contains("/mpa/html/");
    }

    private boolean hasBossSessionCookie() {
        if (context == null) {
            return false;
        }
        try {
            for (Cookie cookie : filterCookiesByDomain(context.cookies(), BOSS_DOMAIN)) {
                if (cookie == null || cookie.name == null || cookie.value == null || cookie.value.isBlank()) {
                    continue;
                }
                String name = cookie.name;
                if ("wt2".equals(name) || "bst".equals(name) || "zp_at".equals(name) || "__zp_stoken__".equals(name)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("读取 Boss 会话 Cookie 失败: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 检查Boss是否已登录
     */
    private boolean checkIfLoggedIn() {
        String currentUrl = safePageUrl(bossPage);
        if (isBossLoginUrl(currentUrl)) {
            return false;
        }
        // 安全校验进行中：不因 DOM 未就绪误判未登录而去 navigate
        if (isBossSecurityUrl(currentUrl)) {
            return hasBossSessionCookie();
        }
        // 求职端工作区 URL 是已登录的强信号（覆盖投递搜索页）
        if (isBossLoggedInWorkspaceUrl(currentUrl)) {
            return true;
        }

        // 更稳健的登录判断：优先检测用户头像/昵称是否可见；备用检测登录入口是否可见且包含“登录”文本
        try {
            Locator userLabel = bossPage.locator("li.nav-figure span.label-text").first();
            if (userLabel.isVisible()) {
                return true;
            }
        } catch (Exception ignored) {}

        try {
            // 有些版本仅展示头像入口，无 label-text
            Locator navFigure = bossPage.locator("li.nav-figure").first();
            if (navFigure.isVisible()) {
                return true;
            }
        } catch (Exception ignored) {}

        try {
            // 未登录时通常有“登录/注册”入口或按钮容器
            Locator loginAnchor = bossPage.locator("li.nav-sign a, .btns").first();
            if (loginAnchor.isVisible()) {
                String text = loginAnchor.textContent();
                if (text != null && text.contains("登录")) {
                    return false;
                }
            }
        } catch (Exception ignored) {}

        // DOM 尚未就绪但已有会话 Cookie：按已登录处理，避免误判后反复 navigate
        if (hasBossSessionCookie() && currentUrl != null && currentUrl.contains("zhipin.com")) {
            return true;
        }

        // 无法明确检测到登录特征时，保守返回未登录
        return false;
    }

    /**
     * 设置登录状态监控
     *
     * @param page 页面实例
     */
    private void setupLoginMonitoring(Page page) {
        // 监听页面导航事件，检测URL变化
        page.onFrameNavigated(frame -> {
            if (frame == page.mainFrame()) {
                // 事件触发的检查在Playwright内部线程执行，仍需遵守暂停标志
                if (!bossMonitoringPaused) {
                    checkLoginStatus(page, "boss");
                }
            }
        });

        log.info("{}平台登录状态监控已启用", "boss");
    }

    /**
     * 设置猎聘平台（加载Cookie、导航、监控）
     */
    private void setupLiepinPlatform() {
        log.info("开始初始化猎聘平台...");

        // 尝试从数据库加载猎聘平台Cookie到上下文
        try {
            CookieEntity cookieEntity = cookieService.getCookieByPlatform("liepin");
            if (cookieEntity != null && cookieEntity.getCookieValue() != null && !cookieEntity.getCookieValue().isBlank()) {
                String cookieStr = cookieEntity.getCookieValue();
                List<Cookie> cookies = filterCookiesByDomain(parseCookiesFromString(cookieStr), LIEPIN_DOMAIN);

                if (!cookies.isEmpty()) {
                    context.addCookies(cookies);
                    log.info("已从数据库加载猎聘 Cookie并注入浏览器上下文，共 {} 条", cookies.size());
                } else {
                    log.warn("解析猎聘Cookie失败，未能加载任何Cookie");
                }
            } else {
                log.info("数据库未找到猎聘Cookie或值为空，跳过Cookie注入");
            }
        } catch (Exception e) {
            log.warn("从数据库加载猎聘Cookie失败: {}", e.getMessage());
        }

        // 导航到猎聘首页（带重试机制）
        int maxRetries = 3;
        boolean navigateSuccess = false;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                liepinPage.navigate(LIEPIN_URL, new Page.NavigateOptions()
                        .setTimeout(60000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                navigateSuccess = true;
                break;
            } catch (Exception e) {
                // Playwright在并发导航时可能抛出 "Object doesn't exist" 异常，但页面实际已加载
                boolean pageAccessible = false;
                try {
                    String url = liepinPage.url();
                    pageAccessible = url != null && url.contains("liepin.com");
                } catch (Exception ignored) {
                }

                if (pageAccessible) {
                    navigateSuccess = true;
                    break;
                }

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        if (!navigateSuccess) {
            log.warn("猎聘页面导航失败");
        }

        // 等待页面网络空闲，确保头部导航渲染完成
        try {
            liepinPage.waitForLoadState(LoadState.NETWORKIDLE);
        } catch (Exception e) {
            log.debug("等待猎聘页面网络空闲失败: {}", e.getMessage());
        }

        // 初始化登录状态并通知（如果有SSE连接会立即推送）
        setLoginStatus("liepin", checkIfLiepinLoggedIn());
        // 设置登录状态监控
        setupLiepinLoginMonitoring(liepinPage);
    }

    /**
     * 检查猎聘是否已登录
     * 已登录：能找到用户头像 <img class="header-quick-menu-user-photo" ...>
     * 未登录：能找到 <span id="header-quick-menu-login">登录/注册</span>
     */
    private boolean checkIfLiepinLoggedIn() {
        try {
            // 先检查“登录/注册”入口是否可见，若可见则明确未登录
            try {
                Locator loginEntry = liepinPage.locator(
                    "#header-quick-menu-login, a[href*='login'], a[data-key='login'], button[data-key='login'], text=/登录|注册/").first();
                if (loginEntry.isVisible()) {
                    log.info("检测到未登录猎聘，保持在登录页或首页等待扫码登录");
                    // 若不在登录页，则导航到登录页并尝试切换二维码
                    String currentUrl = null;
                    try { currentUrl = liepinPage.url(); } catch (Exception ignored) {}
                    try {
                        if (currentUrl == null || !currentUrl.contains("/login")) {
                            liepinPage.navigate("https://www.liepin.com/login");
                            try { Thread.sleep(800); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        }
                        // 优先点击官方切换二维码的容器
                        Locator qrSwitch = liepinPage.locator(".switch-type-mask-img-box").first();
                        if (qrSwitch.isVisible()) {
                            qrSwitch.click();
                            log.info("已切换到猎聘二维码登录页面，等待用户扫码...");
                        } else {
                            // 兼容新版页面：图片资源名包含 qrcode-btn，需要点击其父级按钮
                            Locator qrImg = liepinPage.locator("img[src*='qrcode-btn']").first();
                            if (qrImg.count() > 0 && qrImg.isVisible()) {
                                try {
                                    // 尝试点击父节点或最近的可点击容器
                                    qrImg.click();
                                } catch (Exception ignored) {
                                    try {
                                        Locator parentBtn = qrImg.locator("xpath=ancestor::button[1] | xpath=ancestor::*[contains(@class,'btn')][1]").first();
                                        if (parentBtn.count() > 0 && parentBtn.isVisible()) {
                                            parentBtn.click();
                                        }
                                    } catch (Exception ignored2) {}
                                }
                                log.info("已通过二维码按钮切换到扫码登录状态");
                            }
                        }
                    } catch (Exception e) {
                        log.debug("猎聘登录页引导/二维码切换失败: {}", e.getMessage());
                    }
                    return false;
                }
            } catch (Exception ignored) {}

            // 再检查已登录特征：用户信息容器或用户头像是否存在（无需强制可见）
            try {
                if (liepinPage.locator("#header-quick-menu-user-info").count() > 0) {
                    log.debug("猎聘登录检测：存在用户信息容器，判定已登录");
                    return true;
                }
            } catch (Exception ignored) {}

            try {
                if (liepinPage.locator("img.header-quick-menu-user-photo, .header-quick-menu-user-photo").count() > 0) {
                    log.debug("猎聘登录检测：存在用户头像元素，判定已登录");
                    return true;
                }
            } catch (Exception ignored) {}

            // 兜底：若不存在登录入口且也未找到明确已登录特征，按已登录处理（避免误判）
            try {
                boolean loginEntryExists = liepinPage.locator("#header-quick-menu-login, a[href*='login']").count() > 0;
                if (!loginEntryExists) {
                    log.info("猎聘登录检测：未发现登录入口，兜底判定为已登录");
                    return true;
                }
            } catch (Exception ignored) {}

            // 默认未登录
            log.debug("猎聘登录检测：未匹配到明确特征，判定未登录");
            return false;
        } catch (Exception e) {
            log.debug("猎聘登录检测异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 设置猎聘登录状态监控
     *
     * @param page 页面实例
     */
    private void setupLiepinLoginMonitoring(Page page) {
        // 监听页面导航事件，检测URL变化
        page.onFrameNavigated(frame -> {
            if (frame == page.mainFrame()) {
                if (!liepinMonitoringPaused) {
                    checkLiepinLoginStatus(page);
                }
            }
        });

        log.info("猎聘平台登录状态监控已启用");
    }

    /**
     * 设置51job平台（加载Cookie、导航、监控）
     */
    private void setup51jobPlatform() {
        log.info("开始初始化51job平台...");

        // 尝试从数据库加载51job平台Cookie到上下文
        try {
            CookieEntity cookieEntity = cookieService.getCookieByPlatform("51job");
            if (cookieEntity != null && cookieEntity.getCookieValue() != null && !cookieEntity.getCookieValue().isBlank()) {
                String cookieStr = cookieEntity.getCookieValue();
                List<Cookie> cookies = filterCookiesByDomain(parseCookiesFromString(cookieStr), JOB51_DOMAIN);

                if (!cookies.isEmpty()) {
                    context.addCookies(cookies);
                    log.info("已从数据库加载51job Cookie并注入浏览器上下文，共 {} 条", cookies.size());
                } else {
                    log.warn("解析51job Cookie失败，未能加载任何Cookie");
                }
            } else {
                log.info("数据库未找到51job Cookie或值为空，跳过Cookie注入");
            }
        } catch (Exception e) {
            log.warn("从数据库加载51job Cookie失败: {}", e.getMessage());
        }

        // 导航到51job首页（带重试机制）
        int maxRetries = 3;
        boolean navigateSuccess = false;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                job51Page.navigate(JOB51_URL, new Page.NavigateOptions()
                        .setTimeout(60000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                navigateSuccess = true;
                break;
            } catch (Exception e) {
                // Playwright在并发导航时可能抛出 "Object doesn't exist" 异常，但页面实际已加载
                boolean pageAccessible = false;
                try {
                    String url = job51Page.url();
                    pageAccessible = url != null && url.contains("51job.com");
                } catch (Exception ignored) {
                }

                if (pageAccessible) {
                    navigateSuccess = true;
                    break;
                }

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        if (!navigateSuccess) {
            log.warn("51job页面导航失败");
        }

        try {
            // 检查是否需要登录
            if (!checkIf51jobLoggedIn()) {
                log.info("检测到未登录51job，尝试自动点击登录入口并等待用户登录");

                try {
                    // 优先使用用户提供的选择器：span.login.loginBtnClick
                    Locator loginEntry = job51Page.locator("span.login.loginBtnClick").first();
                    if (loginEntry != null && loginEntry.isVisible()) {
                        loginEntry.click(new Locator.ClickOptions().setTimeout(30000));
                        log.info("已点击 51job 首页的 ‘登录/注册’ 入口，等待用户登录...");
                        asyncWaitFor51jobLogin();
                    } else {
                        // 备用选择器：文本匹配
                        Locator altLoginEntry = job51Page.locator("text=/登录\\/注册|登录|注册/").first();
                        if (altLoginEntry != null && altLoginEntry.isVisible()) {
                            altLoginEntry.click(new Locator.ClickOptions().setTimeout(30000));
                            log.info("已点击 51job 首页的登录入口（文本匹配），等待用户登录...");
                            asyncWaitFor51jobLogin();
                        } else {
                            log.info("未找到 51job 登录入口元素，保持在首页等待用户自行登录");
                            // 启动后台轮询，确保无导航也能检测到登录成功
                            asyncWaitFor51jobLogin();
                        }
                    }
                } catch (Exception clickEx) {
                    log.warn("尝试点击 51job 登录入口时发生异常: {}，保持在首页等待用户登录", clickEx.getMessage());
                    // 启动后台轮询，避免异常导致无法检测登录成功
                    asyncWaitFor51jobLogin();
                }
            } else {
                log.info("51job已登录");
            }
        } catch (Exception e) {
            log.warn("51job页面初始化检查失败: {}", e.getMessage());
        }

        // 初始化登录状态并通知（如果有SSE连接会立即推送）
        setLoginStatus("51job", checkIf51jobLoggedIn());
        // 设置登录状态监控
        setup51jobLoginMonitoring(job51Page);
    }

    /**
     * 检查51job是否已登录
     */
  private boolean checkIf51jobLoggedIn() {
      try {
            // 未登录特征：存在“登录/注册”入口
            Locator loginBtn = job51Page.locator("span.login.loginBtnClick").first();
            if (loginBtn.isVisible()) {
                String txt = (loginBtn.textContent() == null ? "" : loginBtn.textContent()).trim();
                if (txt.contains("登录")) {
                    return false;
                }
            }
            // 已登录特征（增强）：顶部显示用户名入口或个人中心链接
            // 1) 明确的用户名锚点（类名：uname e_icon at）
            Locator userAnchor = job51Page.locator("a.uname.e_icon.at");
            if (userAnchor.count() > 0 && userAnchor.first().isVisible()) {
                return true;
            }
            // 2) 个人中心链接（href=/pc/my/myjob）
            Locator myJobLink = job51Page.locator("a[href*='/pc/my/myjob']");
            if (myJobLink.count() > 0 && myJobLink.first().isVisible()) {
                return true;
            }
            // 3) 其他可能的用户信息容器（旧的兜底选择器）
            return job51Page.locator(".login-info, .user-info, .username").count() > 0;
      } catch (Exception e) {
          return false;
      }
  }

    /**
     * 设置51job登录状态监控
     *
     * @param page 页面实例
     */
    private void setup51jobLoginMonitoring(Page page) {
        // 监听页面导航事件，检测URL变化
        page.onFrameNavigated(frame -> {
            if (frame == page.mainFrame()) {
                if (!job51MonitoringPaused) {
                    check51jobLoginStatus(page);
                }
            }
        });

        log.info("51job平台登录状态监控已启用");
    }

    /**
     * 检查51job登录状态
     *
     * @param page 页面实例
     */
    private void check51jobLoginStatus(Page page) {
        try {
            boolean isLoggedIn = checkIf51jobLoggedIn();
            // 如果登录状态发生变化（从未登录变为已登录）
            Boolean previousStatus = loginStatus.get("51job");
            if (isLoggedIn && (previousStatus == null || !previousStatus)) {
                on51jobLoginSuccess();
            }
        } catch (Exception e) {
            // 忽略检查过程中的异常，避免影响正常流程
            log.debug("检查51job平台登录状态时发生异常: {}", e.getMessage());
        }
    }

    /**
     * 51job登录成功回调
     */
    private void on51jobLoginSuccess() {
        log.info("51job平台登录成功");

        // 更新登录状态并通知
        setLoginStatus("51job", true);

        // 登录成功时保存 Cookie 到数据库
        save51jobCookiesToDatabase("login success");
    }

    /**
     * 在后台异步等待 51job 登录成功。
     * 说明：不阻塞初始化主流程，独立线程每秒轮询一次登录状态，最长等待5分钟。
     */
    private void asyncWaitFor51jobLogin() {
        if (wait51jobLoginThreadRunning || isLoggedIn("51job")) {
            return;
        }
        wait51jobLoginThreadRunning = true;
        Thread waitThread = new Thread(() -> {
            try {
                int maxSeconds = 300;
                for (int i = 0; i < maxSeconds; i++) {
                    if (isLoggedIn("51job")) {
                        return;
                    }
                    boolean loggedIn = false;
                    try {
                        loggedIn = checkIf51jobLoggedIn();
                    } catch (Exception ignored) {
                    }

                    if (loggedIn) {
                        on51jobLoginSuccess();
                        log.info("后台等待检测到 51job 登录成功，用时约 {} 秒", i);
                        return;
                    }

                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.debug("等待 51job 登录线程被中断");
                        return;
                    }
                }
                log.warn("后台等待 51job 登录超时（约5分钟），仍未检测到登录成功");
            } catch (Exception e) {
                log.warn("后台等待 51job 登录过程中发生异常: {}", e.getMessage());
            } finally {
                wait51jobLoginThreadRunning = false;
            }
        }, "wait-51job-login-thread");

        waitThread.setDaemon(true);
        waitThread.start();
    }

    /**
     * 保存51job Cookie到数据库
     *
     * @param remark 备注信息
     */
  private void save51jobCookiesToDatabase(String remark) {
      try {
          List<com.microsoft.playwright.options.Cookie> cookies = filterCookiesByDomain(context.cookies(), JOB51_DOMAIN);
          // 使用ObjectMapper序列化为JSON字符串
          String cookieJson = new ObjectMapper().writeValueAsString(cookies);
          boolean result = cookieService.saveOrUpdateCookie("51job", cookieJson, remark);
          if (result) {
                long now = System.currentTimeMillis();
                boolean shouldInfoLog = (now - last51CookieLogMs) > 15000 // 至少间隔15秒
                        || cookies.size() != last51CookieLogCount
                        || (remark != null && !remark.equals(last51CookieRemark));
                if (shouldInfoLog) {
                    log.info("保存51job Cookie成功，共 {} 条，remark={}", cookies.size(), remark);
                    last51CookieLogMs = now;
                    last51CookieLogCount = cookies.size();
                    last51CookieRemark = remark == null ? "" : remark;
                } else {
                    // 近似重复的频繁调用，改为debug降低噪音
                    log.debug("保存51job Cookie成功(节流)，条数={}，remark={}", cookies.size(), remark);
                }
          }
      } catch (Exception e) {
          log.warn("保存51job Cookie失败: {}", e.getMessage());
      }
  }

    /**
     * 主动保存51job Cookie到数据库（用于调试/验证）
     */
    public void save51jobCookiesToDb(String remark) {
        save51jobCookiesToDatabase(remark);
    }

    /**
     * 清理51job上下文中的Cookie（仅移除 51job 相关域名，不影响其他平台）
     */
    public void clear51jobCookies() {
        clearCookiesByDomain(JOB51_DOMAIN);
    }

    /**
     * 暂停51job页面的后台登录监控（避免与业务流程并发操作页面）
     */
    public void pause51jobMonitoring() {
        job51MonitoringPaused = true;
        log.debug("51job登录监控已暂停");
    }

    /**
     * 恢复51job页面的后台登录监控
     */
    public void resume51jobMonitoring() {
        job51MonitoringPaused = false;
        log.debug("51job登录监控已恢复");
    }

    /**
     * 触发 51job 登录流程：打开登录页并点击“微信扫码登录”按钮
     */
    public void trigger51jobLogin() {
        try {
            openPlatform("51job");
            if (job51Page == null) {
                throw new IllegalStateException("51job页面未初始化");
            }

            // 如果已登录则直接返回
            if (checkIf51jobLoggedIn()) {
                log.info("检测到已登录51job，跳过登录触发");
                return;
            }

            // 先尝试在首页点击“登录/注册”入口
            try {
                job51Page.navigate(JOB51_URL, new Page.NavigateOptions()
                    .setTimeout(60000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                Locator loginEntry = job51Page.locator("span.login.loginBtnClick, text=/登录\\/注册|登录|注册/").first();
                if (loginEntry.isVisible()) {
                    loginEntry.click(new Locator.ClickOptions().setTimeout(DEFAULT_TIMEOUT));
                }
            } catch (Exception e) {
                log.debug("在首页尝试点击登录入口失败: {}", e.getMessage());
            }

            // 跳转到官方登录页
            String loginUrl = "https://login.51job.com/login.php";
            job51Page.navigate(loginUrl, new Page.NavigateOptions()
                .setTimeout(60000)
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            // 尝试点击“微信扫码登录”按钮
            Locator wechatScanBtn = job51Page.locator(
                "i.passIcon.custom-cursor-on-hover[data-sensor-id='sensor_login_wechatScan'], " +
                "i.passIcon[data-sensor-id='sensor_login_wechatScan'], " +
                "[data-sensor-id='sensor_login_wechatScan']"
            ).first();

            if (wechatScanBtn.isVisible()) {
                wechatScanBtn.click(new Locator.ClickOptions().setTimeout(DEFAULT_TIMEOUT));
                log.info("已点击51job登录页的微信扫码按钮，等待用户扫码登录...");
            } else {
                log.warn("未找到微信扫码登录按钮，用户可在登录页自行选择扫码方式");
            }

            // 不阻塞等待：监控会自动检测到登录成功并保存Cookie
        } catch (Exception e) {
            log.error("触发51job登录流程失败: {}", e.getMessage(), e);
            throw new RuntimeException("触发51job登录流程失败", e);
        }
    }

    /**
     * 设置智联招聘平台（加载Cookie、导航、监控）
     */
    private void setupZhilianPlatform() {
        log.info("开始初始化智联招聘平台...");

        // 尝试从数据库加载智联招聘平台Cookie到上下文
        try {
            CookieEntity cookieEntity = cookieService.getCookieByPlatform("zhilian");
            if (cookieEntity != null && cookieEntity.getCookieValue() != null && !cookieEntity.getCookieValue().isBlank()) {
                String cookieStr = cookieEntity.getCookieValue();
                List<Cookie> cookies = filterCookiesByDomain(parseCookiesFromString(cookieStr), ZHILIAN_DOMAIN);

                if (!cookies.isEmpty()) {
                    context.addCookies(cookies);
                    log.info("已从数据库加载智联招聘 Cookie并注入浏览器上下文，共 {} 条", cookies.size());
                } else {
                    log.warn("解析智联招聘Cookie失败，未能加载任何Cookie");
                }
            } else {
                log.info("数据库未找到智联招聘Cookie或值为空，跳过Cookie注入");
            }
        } catch (Exception e) {
            log.warn("从数据库加载智联招聘Cookie失败: {}", e.getMessage());
        }

        // 导航到智联招聘首页（带重试机制）
        int maxRetries = 3;
        boolean navigateSuccess = false;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                zhilianPage.navigate(ZHILIAN_URL, new Page.NavigateOptions()
                        .setTimeout(60000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                navigateSuccess = true;
                break;
            } catch (Exception e) {
                // Playwright在并发导航时可能抛出 "Object doesn't exist" 异常，但页面实际已加载
                boolean pageAccessible = false;
                try {
                    String url = zhilianPage.url();
                    pageAccessible = url != null && url.contains("zhaopin.com");
                } catch (Exception ignored) {
                }

                if (pageAccessible) {
                    navigateSuccess = true;
                    break;
                }

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        if (!navigateSuccess) {
            log.warn("智联招聘页面导航失败");
        }

        // 等待页面加载完成
        try {
            zhilianPage.waitForLoadState(LoadState.NETWORKIDLE);
        } catch (Exception e) {
            log.debug("等待智联页面网络空闲失败: {}", e.getMessage());
        }

        // 初始化登录状态并通知（如果有SSE连接会立即推送）
        setLoginStatus("zhilian", checkIfZhilianLoggedIn());
        // 设置登录状态监控
        setupZhilianLoginMonitoring(zhilianPage);
    }

    /**
     * 检查智联招聘是否已登录
     * 未登录时只在首次检测时引导用户到登录页
     */
    private boolean checkIfZhilianLoggedIn() {
        try {
            if (zhilianPage == null) {
                return false;
            }

            boolean isLoggedIn = false;
            boolean loginButtonExists = false;

            // 检查是否存在"登录/注册"按钮
            try {
                Locator loginButton = zhilianPage.locator("a.home-header__c-no-login").first();
                int count = loginButton.count();
                if (count > 0) {
                    loginButtonExists = true;
                    // 尝试获取文本进一步确认
                    try {
                        String buttonText = loginButton.textContent();
                        if (buttonText != null && buttonText.contains("登录")) {
                            loginButtonExists = true;
                        }
                    } catch (Exception e) {
                        log.debug("智联招聘：获取登录按钮文本失败: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.debug("智联招聘：检查登录按钮时异常: {}", e.getMessage());
            }

            // 如果存在登录按钮，说明未登录
            if (loginButtonExists) {
                // 只在首次检测到未登录时执行引导操作
                if (!zhilianLoginGuided) {
                    log.info("检测到未登录智联招聘，重定向到登录页面");
                    zhilianLoginGuided = true;

                    // 重定向到登录页面
                    String currentUrl = null;
                    try {
                        currentUrl = zhilianPage.url();
                    } catch (Exception ignored) {
                    }

                    try {
                        if (currentUrl == null || !currentUrl.contains("passport.zhaopin.com/login")) {
                            boolean loginNavOk = false;
                            try {
                                zhilianPage.navigate(
                                        "https://passport.zhaopin.com/login",
                                        new Page.NavigateOptions()
                                                .setTimeout(60000)
                                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                                );
                                loginNavOk = true;
                            } catch (Exception navEx) {
                                String urlAfter = null;
                                try {
                                    urlAfter = zhilianPage.url();
                                } catch (Exception ignored2) {}

                                if (urlAfter != null && urlAfter.contains("passport.zhaopin.com")) {
                                    loginNavOk = true;
                                    log.debug("智联招聘：登录页导航异常但已在登录域: {}", navEx.getMessage());
                                } else {
                                    log.warn("智联招聘：导航至登录页失败: {}", navEx.getMessage());
                                }
                            }

                            if (loginNavOk) {
                                try {
                                    zhilianPage.waitForLoadState(LoadState.DOMCONTENTLOADED);
                                } catch (Exception ignored) {}
                                try {
                                    zhilianPage.waitForSelector(
                                            "div.zppp-panel-normal-bar__img, " +
                                            "div.passport-login, #J_loginWrap, " +
                                            "div[class*='qrcode'], img[src*='qrcode']",
                                            new Page.WaitForSelectorOptions().setTimeout(30000)
                                    );
                                } catch (Exception e) {
                                    log.debug("智联招聘：登录页关键元素等待失败: {}", e.getMessage());
                                }
                            }
                        }

                        // 点击二维码登录按钮
                        Locator qrToggle = zhilianPage.locator("div.zppp-panel-normal-bar__img").first();
                        if (qrToggle.count() > 0 && qrToggle.isVisible()) {
                            qrToggle.click(new Locator.ClickOptions().setTimeout(DEFAULT_TIMEOUT));
                            log.info("已切换到智联二维码登录页面，等待用户扫码...");
                        } else {
                            log.info("智联招聘登录页面已打开，等待用户扫码...");
                        }
                    } catch (Exception e) {
//                        log.warn("智联招聘：打开二维码登录面板失败: {}", e.getMessage());
                    }
                }
                return false;
            }

            // 检查是否有已登录的特征
            try {
                String url = zhilianPage.url();
                if (url != null && url.contains("i.zhaopin.com")) {
                    log.debug("智联招聘：URL包含i.zhaopin.com，判定为已登录");
                    isLoggedIn = true;
                }
            } catch (Exception ignore) {
            }

            // 如果没有登录按钮，也认为已登录
            if (!loginButtonExists) {
                log.debug("智联招聘：未检测到登录按钮，判定为已登录");
                isLoggedIn = true;
            }

            return isLoggedIn;
        } catch (Exception e) {
            log.warn("智联招聘：检查登录状态异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 设置智联招聘登录状态监控
     *
     * @param page 页面实例
     */
    private void setupZhilianLoginMonitoring(Page page) {
        // 监听页面导航事件，检测URL变化
        page.onFrameNavigated(frame -> {
            if (frame == page.mainFrame()) {
                if (!zhilianMonitoringPaused) {
                    checkZhilianLoginStatus(page);
                }
            }
        });

        log.info("智联招聘平台登录状态监控已启用");
    }

    /**
     * 检查智联招聘登录状态
     *
     * @param page 页面实例
     */
    private void checkZhilianLoginStatus(Page page) {
        try {
            boolean isLoggedIn = checkIfZhilianLoggedIn();
            // 如果登录状态发生变化（从未登录变为已登录）
            Boolean previousStatus = loginStatus.get("zhilian");
            if (isLoggedIn && (previousStatus == null || !previousStatus)) {
                onZhilianLoginSuccess();
            }
        } catch (Exception e) {
            // 忽略检查过程中的异常，避免影响正常流程
            log.debug("检查智联招聘平台登录状态时发生异常: {}", e.getMessage());
        }
    }

    /**
     * 主动触发智联招聘登录：点击二维码入口并等待登录成功跳转
     */
    public void triggerZhilianLogin() {
        try {
            openPlatform("zhilian");
            if (zhilianPage == null) {
                throw new IllegalStateException("智联招聘页面未初始化");
            }

            // 导航到智联首页，确保DOM就绪
            zhilianPage.navigate(ZHILIAN_URL, new Page.NavigateOptions()
                    .setTimeout(60000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            // 如果看到未登录入口，尝试打开二维码登录面板
            Locator noLoginAnchor = zhilianPage.locator("a.home-header__c-no-login").first();
            if (noLoginAnchor.isVisible()) {
                Locator qrToggle = zhilianPage.locator("div.zppp-panel-normal-bar__img").first();
                if (qrToggle.isVisible()) {
                    qrToggle.click(new Locator.ClickOptions().setTimeout(DEFAULT_TIMEOUT));
                    log.info("已点击智联二维码登录入口，等待用户扫码...");
                } else {
                    log.warn("未找到二维码登录入口元素：div.zppp-panel-normal-bar__img");
                }
            } else {
                log.info("未检测到未登录入口，可能已登录或在其他页面");
            }

            // 监听登录成功：等待URL跳转到 i.zhaopin.com 或用户信息元素出现
            try {
                zhilianPage.waitForURL("**i.zhaopin.com**", new Page.WaitForURLOptions().setTimeout(120_000));
                onZhilianLoginSuccess();
                return;
            } catch (Exception ignored) {
            }
            try {
                zhilianPage.waitForSelector(".user-info, .user-name, .username-text", new Page.WaitForSelectorOptions().setTimeout(120_000));
                onZhilianLoginSuccess();
            } catch (Exception e) {
                log.warn("等待智联登录成功超时或失败: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.error("触发智联登录流程失败: {}", e.getMessage(), e);
            throw new RuntimeException("触发智联登录流程失败", e);
        }
    }

    /**
     * 智联招聘登录成功回调
     */
    private void onZhilianLoginSuccess() {
        log.info("智联招聘平台登录成功");

        // 更新登录状态并通知
        setLoginStatus("zhilian", true);

        // 登录成功时保存 Cookie 到数据库
        saveZhilianCookiesToDatabase("login success");
    }

    /**
     * 保存智联招聘Cookie到数据库
     *
     * @param remark 备注信息
     */
    private void saveZhilianCookiesToDatabase(String remark) {
        try {
            List<com.microsoft.playwright.options.Cookie> cookies = filterCookiesByDomain(context.cookies(), ZHILIAN_DOMAIN);
            // 使用ObjectMapper序列化为JSON字符串
            String cookieJson = new ObjectMapper().writeValueAsString(cookies);
            boolean result = cookieService.saveOrUpdateCookie("zhilian", cookieJson, remark);
            if (result) {
                log.info("保存智联招聘Cookie成功，共 {} 条，remark={}", cookies.size(), remark);
            }
        } catch (Exception e) {
            log.warn("保存智联招聘Cookie失败: {}", e.getMessage());
        }
    }

    /**
     * 主动保存智联招聘Cookie到数据库（用于调试/验证）
     */
    public void saveZhilianCookiesToDb(String remark) {
        saveZhilianCookiesToDatabase(remark);
    }

    /**
     * 统一按平台保存 Cookie 到数据库
     *
     * @param platform 平台标识（boss/liepin/51job/zhilian）
     * @param remark   备注
     */
    public void saveCookiesToDb(String platform, String remark) {
        switch (platform) {
            case "boss" -> saveBossCookiesToDatabase(remark);
            case "liepin" -> saveLiepinCookiesToDatabase(remark);
            case "51job" -> save51jobCookiesToDatabase(remark);
            case "zhilian" -> saveZhilianCookiesToDatabase(remark);
            default -> throw new IllegalArgumentException("Unsupported platform: " + platform);
        }
    }

    /**
     * 清理智联招聘上下文中的Cookie（仅移除智联相关域名，不影响其他平台）
     */
    public void clearZhilianCookies() {
        clearCookiesByDomain(ZHILIAN_DOMAIN);
    }

    /**
     * 暂停智联招聘页面的后台登录监控（避免与业务流程并发操作页面）
     */
    public void pauseZhilianMonitoring() {
        zhilianMonitoringPaused = true;
        log.debug("智联招聘登录监控已暂停");
    }

    /**
     * 恢复智联招聘页面的后台登录监控
     */
    public void resumeZhilianMonitoring() {
        zhilianMonitoringPaused = false;
        log.debug("智联招聘登录监控已恢复");
    }

    /**
     * 检查猎聘登录状态
     *
     * @param page 页面实例
     */
    private void checkLiepinLoginStatus(Page page) {
        try {
            boolean isLoggedIn = checkIfLiepinLoggedIn();
            // 如果登录状态发生变化（从未登录变为已登录）
            Boolean previousStatus = loginStatus.get("liepin");
            if (isLoggedIn && (previousStatus == null || !previousStatus)) {
                onLiepinLoginSuccess();
            }
        } catch (Exception e) {
            // 忽略检查过程中的异常，避免影响正常流程
            log.debug("检查猎聘平台登录状态时发生异常: {}", e.getMessage());
        }
    }

    /**
     * 猎聘登录成功回调
     */
    private void onLiepinLoginSuccess() {
        log.info("猎聘平台登录成功");

        // 更新登录状态并通知
        setLoginStatus("liepin", true);

        // 登录成功时保存 Cookie 到数据库
        saveLiepinCookiesToDatabase("login success");
    }

    /**
     * 保存猎聘Cookie到数据库
     *
     * @param remark 备注信息
     */
    private void saveLiepinCookiesToDatabase(String remark) {
        try {
            List<com.microsoft.playwright.options.Cookie> cookies = filterCookiesByDomain(context.cookies(), LIEPIN_DOMAIN);
            // 使用ObjectMapper序列化为JSON字符串
            String cookieJson = new ObjectMapper().writeValueAsString(cookies);
            boolean result = cookieService.saveOrUpdateCookie("liepin", cookieJson, remark);
            if (result) {
                log.info("保存猎聘Cookie成功，共 {} 条，remark={}", cookies.size(), remark);
            }
        } catch (Exception e) {
            log.warn("保存猎聘Cookie失败: {}", e.getMessage());
        }
    }

    /**
     * 主动保存猎聘Cookie到数据库（用于调试/验证）
     */
    public void saveLiepinCookiesToDb(String remark) {
        saveLiepinCookiesToDatabase(remark);
    }

    /**
     * 清理猎聘上下文中的Cookie（仅移除猎聘相关域名，不影响其他平台）
     */
    public void clearLiepinCookies() {
        clearCookiesByDomain(LIEPIN_DOMAIN);
    }

    /**
     * 暂停猎聘页面的后台登录监控（避免与业务流程并发操作页面）
     */
    public void pauseLiepinMonitoring() {
        liepinMonitoringPaused = true;
        log.debug("猎聘登录监控已暂停");
    }

    /**
     * 恢复猎聘页面的后台登录监控
     */
    public void resumeLiepinMonitoring() {
        liepinMonitoringPaused = false;
        log.debug("猎聘登录监控已恢复");
    }

    /**
     * 检查登录状态
     *
     * @param page     页面实例
     * @param platform 平台名称
     */
    private void checkLoginStatus(Page page, String platform) {
        try {
            boolean isLoggedIn = false;
            if (platform.equals("boss")) {
                // 统一复用更稳健的Boss登录判断逻辑
                isLoggedIn = checkIfLoggedIn();
            }
            // 如果登录状态发生变化（从未登录变为已登录）
            Boolean previousStatus = loginStatus.get(platform);
            if (isLoggedIn && (previousStatus == null || !previousStatus)) {
                onLoginSuccess(platform);
            }
        } catch (Exception e) {
            // 忽略检查过程中的异常，避免影响正常流程
            log.debug("检查{}平台登录状态时发生异常: {}", platform, e.getMessage());
        }
    }

    /**
     * 登录成功回调
     *
     * @param platform 平台名称
     */
    private void onLoginSuccess(String platform) {
        log.info("{}平台登录成功", platform);

        // 更新登录状态并通知（统一使用setLoginStatus方法）
        setLoginStatus(platform, true);
        if ("boss".equals(platform)) {
            bossLoginGuided = true;
            saveBossCookiesToDatabase("login success");
        }
    }

    /**
     * 统一的Boss Cookie保存方法（使用JSON序列化）
     *
     * @param remark 备注信息
     */
    private void saveBossCookiesToDatabase(String remark) {
        try {
            List<com.microsoft.playwright.options.Cookie> cookies = filterCookiesByDomain(context.cookies(), BOSS_DOMAIN);
            // 使用ObjectMapper序列化为JSON字符串
            String cookieJson = new ObjectMapper().writeValueAsString(cookies);
            boolean result = cookieService.saveOrUpdateCookie("boss", cookieJson, remark);
            if (result) {
                log.info("保存Boss Cookie成功，共 {} 条，remark={}", cookies.size(), remark);
            }
        } catch (Exception e) {
            log.warn("保存Boss Cookie失败: {}", e.getMessage());
        }
    }

    /**
     * 主动保存 Boss Cookie 到数据库（用于调试/验证）
     */
    public void saveBossCookiesToDb(String remark) {
        saveBossCookiesToDatabase(remark);
    }

    /**
     * 清理Boss上下文中的Cookie（仅移除 Boss 相关域名，不影响其他平台）
     */
    public void clearBossCookies() {
        clearCookiesByDomain(BOSS_DOMAIN);
        bossLoginGuided = false;
        setLoginStatus("boss", false);
    }

    /**
     * 按域名清理 Cookie，保留其他平台的登录态。
     * Playwright 无按域删除 API，因此先保留非目标域 Cookie，再全量清除后写回。
     */
    private void clearCookiesByDomain(String domainSuffix) {
        if (context == null) {
            log.warn("共享上下文不存在，无法清理 {} Cookie", domainSuffix);
            return;
        }
        try {
            List<Cookie> all = context.cookies();
            List<Cookie> toKeep = new ArrayList<>();
            int removed = 0;
            for (Cookie cookie : all) {
                if (cookie == null) {
                    continue;
                }
                if (cookie.domain == null || cookie.domain.isBlank()) {
                    toKeep.add(cookie);
                    continue;
                }
                if (matchesDomainSuffix(cookie.domain, domainSuffix)) {
                    removed++;
                } else {
                    toKeep.add(cookie);
                }
            }
            context.clearCookies();
            if (!toKeep.isEmpty()) {
                context.addCookies(toKeep);
            }
            log.info("已清理 {} 域名 Cookie {} 条，保留其他平台 {} 条", domainSuffix, removed, toKeep.size());
        } catch (Exception e) {
            log.error("清理 {} Cookie 失败: {}", domainSuffix, e.getMessage(), e);
            throw new RuntimeException("清理 " + domainSuffix + " Cookie 失败", e);
        }
    }

    private boolean matchesDomainSuffix(String cookieDomain, String domainSuffix) {
        if (cookieDomain == null || domainSuffix == null) {
            return false;
        }
        String domain = cookieDomain.toLowerCase(Locale.ROOT);
        String suffix = domainSuffix.toLowerCase(Locale.ROOT);
        return domain.equals(suffix) || domain.endsWith("." + suffix);
    }

    /**
     * 定时检查登录状态（仅检查当前活跃平台，降低资源占用）
     */
    @Scheduled(fixedDelay = 10000)
    public void scheduledLoginCheck() {
        String platform = activePlatform;
        if (platform == null) {
            return;
        }
        try {
            switch (platform) {
                case "liepin" -> {
                    if (liepinPage != null && !liepinMonitoringPaused && !isLoggedIn("liepin")) {
                        checkLiepinLoginStatus(liepinPage);
                    }
                }
                case "boss" -> {
                    if (bossPage != null && !bossMonitoringPaused && !isLoggedIn("boss")) {
                        checkLoginStatus(bossPage, "boss");
                    }
                }
                case "51job" -> {
                    if (job51Page != null && !job51MonitoringPaused && !isLoggedIn("51job")) {
                        check51jobLoginStatus(job51Page);
                    }
                }
                case "zhilian" -> {
                    if (zhilianPage != null && !zhilianMonitoringPaused && !isLoggedIn("zhilian")) {
                        checkZhilianLoginStatus(zhilianPage);
                    }
                }
                default -> { }
            }
        } catch (Exception e) {
            log.debug("定时登录检测异常: {}", e.getMessage());
        }
    }

    /**
     * 暂停Boss页面的后台登录监控（避免与业务流程并发操作页面）
     */
    public void pauseBossMonitoring() {
        bossMonitoringPaused = true;
        log.debug("Boss登录监控已暂停");
    }

    /**
     * 恢复Boss页面的后台登录监控
     */
    public void resumeBossMonitoring() {
        bossMonitoringPaused = false;
        log.debug("Boss登录监控已恢复");
    }

    /**
     * 关闭Playwright实例
     * 在Spring容器销毁前自动执行
     */
    @PreDestroy
    public void destroy() {
        log.info("开始关闭Playwright管理器...");
        activePlatform = null;

        try {
            // 关闭所有页面
            if (bossPage != null) {
                bossPage.close();
                log.info("Boss直聘页面已关闭");
            }
            if (liepinPage != null) {
                liepinPage.close();
                log.info("猎聘页面已关闭");
            }
            if (job51Page != null) {
                job51Page.close();
                log.info("51job页面已关闭");
            }
            if (zhilianPage != null) {
                zhilianPage.close();
                log.info("智联招聘页面已关闭");
            }
            if (managementPage != null) {
                managementPage.close();
                log.info("管理页面已关闭");
            }

            // 关闭共享的BrowserContext
            if (context != null) {
                context.close();
                log.info("共享BrowserContext已关闭");
            }

            // 关闭浏览器
            if (browser != null) {
                browser.close();
                log.info("浏览器已关闭");
            }
            if (playwright != null) {
                playwright.close();
                log.info("Playwright实例已关闭");
            }

            log.info("Playwright管理器关闭完成！");
        } catch (Exception e) {
            log.error("关闭Playwright管理器时发生错误", e);
        }
    }

    /**
     * 检查浏览器引擎是否已就绪（不要求任何平台 Page 已创建）
     */
    public boolean isInitialized() {
        return playwright != null && browser != null && context != null;
    }

    public boolean isPlatformOpen(String platform) {
        return switch (normalizePlatform(platform)) {
            case "boss" -> bossPage != null && !bossPage.isClosed();
            case "liepin" -> liepinPage != null && !liepinPage.isClosed();
            case "51job" -> job51Page != null && !job51Page.isClosed();
            case "zhilian" -> zhilianPage != null && !zhilianPage.isClosed();
            default -> false;
        };
    }

    /**
     * 获取CDP端口号
     */
    public int getCdpPort() {
        return CDP_PORT;
    }

    /**
     * 注册登录状态监听器
     *
     * @param listener 监听器
     */
    public void addLoginStatusListener(Consumer<LoginStatusChange> listener) {
        loginStatusListeners.add(listener);
    }

    /**
     * 移除登录状态监听器
     *
     * @param listener 监听器
     */
    public void removeLoginStatusListener(Consumer<LoginStatusChange> listener) {
        loginStatusListeners.remove(listener);
    }

    /**
     * 获取平台登录状态
     *
     * @param platform 平台名称
     * @return 是否已登录
     */
    public boolean isLoggedIn(String platform) {
        return loginStatus.getOrDefault(platform, false);
    }

    /**
     * 手动设置平台登录状态（会触发SSE通知）
     *
     * @param platform   平台名称
     * @param isLoggedIn 是否已登录
     */
    public void setLoginStatus(String platform, boolean isLoggedIn) {
        Boolean previousStatus = loginStatus.get(platform);

        // 只有状态真正发生变化时才更新和通知
        if (previousStatus == null || previousStatus != isLoggedIn) {
            loginStatus.put(platform, isLoggedIn);

            // 通知所有监听器（触发SSE推送）
            // 注意：不要在这里对 Boss 执行 navigate / 点击扫码，否则未登录态的每次状态写入都会刷新登录页
            LoginStatusChange change = new LoginStatusChange(platform, isLoggedIn, System.currentTimeMillis());
            loginStatusListeners.forEach(listener -> {
                try {
                    listener.accept(change);
                } catch (Exception e) {
                    log.error("通知登录状态监听器失败: platform={}, isLoggedIn={}", platform, isLoggedIn, e);
                }
            });
        }
    }

    /**
     * 从JSON字符串解析Cookie列表
     *
     * @param cookieJson Cookie的JSON字符串
     * @return Cookie列表
     */
    private List<Cookie> parseCookiesFromString(String cookieJson) {
        List<Cookie> cookies = new ArrayList<>();

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode jsonArray = objectMapper.readTree(cookieJson);

            for (com.fasterxml.jackson.databind.JsonNode node : jsonArray) {
                // 创建Cookie对象（name和value是必需的）
                Cookie cookie = new Cookie(
                        node.get("name").asText(),
                        node.get("value").asText()
                );

                // 设置可选字段
                if (node.has("domain") && !node.get("domain").isNull()) {
                    cookie.domain = node.get("domain").asText();
                }
                if (node.has("path") && !node.get("path").isNull()) {
                    cookie.path = node.get("path").asText();
                }
                if (node.has("expires") && !node.get("expires").isNull()) {
                    cookie.expires = node.get("expires").asDouble();
                }
                if (node.has("httpOnly") && !node.get("httpOnly").isNull()) {
                    cookie.httpOnly = node.get("httpOnly").asBoolean();
                }
                if (node.has("secure") && !node.get("secure").isNull()) {
                    cookie.secure = node.get("secure").asBoolean();
                }
                if (node.has("sameSite") && !node.get("sameSite").isNull()) {
                    String sameSite = node.get("sameSite").asText();
                    if (sameSite != null && !sameSite.isEmpty()) {
                        cookie.sameSite = com.microsoft.playwright.options.SameSiteAttribute.valueOf(
                                sameSite.toUpperCase()
                        );
                    }
                }

                cookies.add(cookie);
            }

            log.debug("成功解析Cookie，共 {} 条", cookies.size());
        } catch (Exception e) {
            log.error("解析Cookie JSON失败: {}", e.getMessage(), e);
        }

        return cookies;
    }

    private List<Cookie> filterCookiesByDomain(List<Cookie> cookies, String domainSuffix) {
        if (cookies == null || cookies.isEmpty()) {
            return new ArrayList<>();
        }

        String suffix = domainSuffix == null ? "" : domainSuffix.toLowerCase(Locale.ROOT);
        List<Cookie> filtered = new ArrayList<>();
        for (Cookie cookie : cookies) {
            if (cookie == null || cookie.domain == null || cookie.domain.isBlank()) {
                continue;
            }
            String domain = cookie.domain.toLowerCase(Locale.ROOT);
            if (domain.equals(suffix) || domain.endsWith("." + suffix)) {
                filtered.add(cookie);
            }
        }

        return filtered;
    }

    /**
     * LoginStatusChange - 登录状态变化DTO
     */
    public record LoginStatusChange(String platform, boolean isLoggedIn, long timestamp) {
    }
}
