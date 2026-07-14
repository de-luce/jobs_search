package com.getjobs.application.controller;

import com.getjobs.worker.manager.PlaywrightManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Playwright管理控制器
 * 用于测试和管理Playwright实例
 */
@RestController
@RequestMapping("/api/playwright")
public class PlaywrightController {

    private final PlaywrightManager playwrightManager;

    public PlaywrightController(PlaywrightManager playwrightManager) {
        this.playwrightManager = playwrightManager;
    }

    /**
     * 获取Playwright状态信息
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("initialized", playwrightManager.isInitialized());
        status.put("cdpPort", playwrightManager.getCdpPort());
        status.put("activePlatform", playwrightManager.getActivePlatform());
        status.put("hasBrowser", playwrightManager.getBrowser() != null);
        status.put("hasBossPage", playwrightManager.isPlatformOpen("boss"));
        status.put("hasLiepinPage", playwrightManager.isPlatformOpen("liepin"));
        status.put("has51jobPage", playwrightManager.isPlatformOpen("51job"));
        status.put("hasZhilianPage", playwrightManager.isPlatformOpen("zhilian"));
        status.put("bossLoggedIn", playwrightManager.isLoggedIn("boss"));
        status.put("liepinLoggedIn", playwrightManager.isLoggedIn("liepin"));
        status.put("job51LoggedIn", playwrightManager.isLoggedIn("51job"));
        status.put("zhilianLoggedIn", playwrightManager.isLoggedIn("zhilian"));

        return ResponseEntity.ok(status);
    }

    /**
     * 按需打开指定平台（单平台模式，切换时关闭其他平台标签页）
     */
    /**
     * 在 CDP 浏览器中打开/同步管理前台（与招聘站点共用同一 Chrome 实例）
     */
    @PostMapping("/management/open")
    public ResponseEntity<Map<String, Object>> openManagementPage(
            @org.springframework.web.bind.annotation.RequestBody(required = false) Map<String, String> body) {
        try {
            String url = body != null && body.get("url") != null && !body.get("url").isBlank()
                    ? body.get("url").trim()
                    : "http://127.0.0.1:6866";
            playwrightManager.openManagementPage(url);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("url", url);
            result.put("cdpPort", playwrightManager.getCdpPort());
            result.put("message", "管理页已在自动化浏览器中打开");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "打开管理页失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    @PostMapping("/platform/{platform}/open")
    public ResponseEntity<Map<String, Object>> openPlatform(
            @PathVariable String platform,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean focus,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean forceLogin) {
        try {
            return ResponseEntity.ok(playwrightManager.openPlatform(platform, focus, forceLogin));
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "打开平台失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * 测试Boss导航功能
     */
    @GetMapping("/test-navigate")
    public ResponseEntity<Map<String, String>> testNavigate() {
        try {
            playwrightManager.openPlatform("boss");
            playwrightManager.getBossPage().navigate("https://www.zhipin.com");
            String title = playwrightManager.getBossPage().title();

            Map<String, String> result = new HashMap<>();
            result.put("success", "true");
            result.put("title", title);
            result.put("url", playwrightManager.getBossPage().url());

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("success", "false");
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}
