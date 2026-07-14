package com.getjobs.application.config;

import com.getjobs.worker.manager.PlaywrightManager;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 应用启动后：
 * 1. 确保前端管理页可访问（未启动则自动 npm run dev）
 * 2. 使用 Playwright Chrome 打开管理页面
 * 3. 后端关闭时一并结束由本进程拉起的前端 dev 服务
 */
@Slf4j
@Component
public class StartupRunner implements ApplicationRunner {

    private static final int FRONTEND_PORT = 6866;
    private static final String FRONTEND_HOST = "127.0.0.1";
    private static final String FRONTEND_URL = "http://" + FRONTEND_HOST + ":" + FRONTEND_PORT;
    private static final int FRONTEND_START_TIMEOUT_SEC = 120;

    @Value("${server.port:8888}")
    private int backendPort;

    @Resource
    private PlaywrightManager playwrightManager;

    /** 仅保存「本进程启动」的前端，外部已有实例不会被关掉 */
    private final AtomicReference<Process> ownedFrontendProcess = new AtomicReference<>();

    @Override
    public void run(ApplicationArguments args) {
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopOwnedFrontend, "frontend-shutdown-hook"));

        Thread starter = new Thread(() -> {
            try {
                String url = ensureFrontendReady();
                if (url == null) {
                    log.warn("无法就绪前端管理页，跳过自动打开 Chrome");
                    return;
                }
                playwrightManager.openManagementPage(url);
                log.info("已使用 CDP 浏览器打开管理页面: {}", url);
                log.info("管理页与招聘站点共用同一 Chrome（端口 {}），切换路由将自动同步", playwrightManager.getCdpPort());
            } catch (Exception e) {
                log.error("启动后打开管理页面失败: {}", e.getMessage(), e);
            }
        }, "startup-management");
        starter.setDaemon(true);
        starter.start();
        log.info("已在后台准备前端管理页并打开 Chrome...");
    }

    @PreDestroy
    public void onShutdown() {
        stopOwnedFrontend();
    }

    /**
     * 确保前端可访问，返回应打开的 URL
     */
    private String ensureFrontendReady() throws InterruptedException, IOException {
        if (isServiceRunning(FRONTEND_PORT)) {
            log.info("检测到前端服务已在端口 {} 运行（外部进程，后端退出时不会关闭）", FRONTEND_PORT);
            return FRONTEND_URL;
        }

        if (hasStaticResources()) {
            log.info("未检测到前端 dev 服务，但存在静态资源，等待端口 {} 就绪", FRONTEND_PORT);
            if (waitForService(FRONTEND_PORT, 15)) {
                return FRONTEND_URL;
            }
        }

        Path frontDir = resolveFrontDirectory();
        if (frontDir == null) {
            log.warn("未找到 front 目录，无法自动启动前端");
            return fallbackBackendUrl();
        }

        if (!isCommandAvailable("npm")) {
            log.warn("未检测到 npm 命令，无法自动启动前端");
            return fallbackBackendUrl();
        }

        log.info("前端未启动，正在自动执行: cd {} && npm run dev", frontDir);
        startFrontendDevServer(frontDir);

        if (waitForService(FRONTEND_PORT, FRONTEND_START_TIMEOUT_SEC)) {
            log.info("前端 dev 服务已就绪: {}", FRONTEND_URL);
            return FRONTEND_URL;
        }

        log.warn("等待前端启动超时（{}秒），请手动执行: cd front && npm run dev", FRONTEND_START_TIMEOUT_SEC);
        return null;
    }

    private String fallbackBackendUrl() {
        if (hasStaticResources()) {
            return "http://localhost:" + backendPort;
        }
        return null;
    }

    private Path resolveFrontDirectory() {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path direct = cwd.resolve("front");
        if (Files.isRegularFile(direct.resolve("package.json"))) {
            return direct;
        }
        Path parent = cwd.getParent();
        if (parent != null) {
            Path sibling = parent.resolve("front");
            if (Files.isRegularFile(sibling.resolve("package.json"))) {
                return sibling;
            }
        }
        return null;
    }

    private void startFrontendDevServer(Path frontDir) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        ProcessBuilder processBuilder;
        if (os.contains("win")) {
            processBuilder = new ProcessBuilder("cmd.exe", "/c", "npm", "run", "dev");
        } else {
            // 用 bash -lc 便于带上用户 PATH；子进程挂到可追踪的进程树
            processBuilder = new ProcessBuilder("npm", "run", "dev");
        }
        processBuilder.directory(frontDir.toFile());
        processBuilder.redirectErrorStream(true);
        File logFile = frontDir.resolve("auto-dev-server.log").toFile();
        processBuilder.redirectOutput(Redirect.appendTo(logFile));
        Process process = processBuilder.start();
        ownedFrontendProcess.set(process);
        log.info("前端 dev 进程已后台启动 (pid={})，日志: {}", process.pid(), logFile.getAbsolutePath());
    }

    private void stopOwnedFrontend() {
        Process process = ownedFrontendProcess.getAndSet(null);
        if (process == null) {
            return;
        }
        if (!process.isAlive()) {
            log.info("前端 dev 进程已退出，无需关闭");
            return;
        }
        log.info("后端关闭，正在结束前端 dev 进程树 (pid={})...", process.pid());
        try {
            destroyProcessTree(process, false);
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                log.warn("前端进程未在时限内退出，强制结束");
                destroyProcessTree(process, true);
                process.waitFor(3, TimeUnit.SECONDS);
            }
            log.info("前端 dev 进程已关闭");
        } catch (Exception e) {
            log.warn("关闭前端进程失败: {}", e.getMessage());
            try {
                destroyProcessTree(process, true);
            } catch (Exception ignored) {
            }
        }
    }

    private void destroyProcessTree(Process process, boolean forcibly) {
        try {
            ProcessHandle handle = process.toHandle();
            // 先杀子孙（vite/node），再杀自身
            handle.descendants().forEach(child -> {
                try {
                    if (forcibly) {
                        child.destroyForcibly();
                    } else {
                        child.destroy();
                    }
                } catch (Exception ignored) {
                }
            });
            if (forcibly) {
                handle.destroyForcibly();
            } else {
                handle.destroy();
            }
        } catch (Exception e) {
            if (forcibly) {
                process.destroyForcibly();
            } else {
                process.destroy();
            }
        }
    }

    private boolean isCommandAvailable(String command) {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            ProcessBuilder pb = os.contains("win")
                    ? new ProcessBuilder("cmd.exe", "/c", "where", command)
                    : new ProcessBuilder("sh", "-c", "command -v " + command);
            Process process = pb.start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean waitForService(int port, int timeoutSeconds) throws InterruptedException {
        for (int i = 0; i < timeoutSeconds; i++) {
            if (isServiceRunning(port)) {
                return true;
            }
            Thread.sleep(1000);
        }
        return false;
    }

    private boolean isServiceRunning(int port) {
        String[] hosts = {FRONTEND_HOST, "127.0.0.1", "[::1]", "localhost"};
        for (String host : hosts) {
            try {
                HttpURLConnection connection = (HttpURLConnection) URI.create("http://" + host + ":" + port)
                        .toURL()
                        .openConnection();
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(2000);
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(false);
                int responseCode = connection.getResponseCode();
                connection.disconnect();
                if (responseCode >= 200 && responseCode < 500) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private boolean hasStaticResources() {
        try {
            File distDir = new File("src/main/resources/dist");
            if (distDir.exists() && distDir.isDirectory()) {
                File[] files = distDir.listFiles(file -> !file.getName().startsWith("."));
                return files != null && files.length > 0;
            }
        } catch (Exception e) {
            log.debug("检查静态资源时出错: {}", e.getMessage());
        }
        return false;
    }
}
