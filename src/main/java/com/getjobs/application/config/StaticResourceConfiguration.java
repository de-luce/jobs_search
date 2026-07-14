package com.getjobs.application.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/**
 * 提供 Vue 前端静态资源（文件系统 dist 或 jar 内 classpath:/dist/）。
 */
@Slf4j
@Configuration
public class StaticResourceConfiguration implements WebMvcConfigurer {
    private static final int FRONTEND_PORT = 6866;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        boolean hasFrontendService = detectFrontendService();
        boolean hasResources = StaticResourceLocator.hasStaticResources();

        if (!hasResources) {
            log.warn("未找到前端静态资源 (resources/dist 或 classpath:/dist/)");
            return;
        }

        log.info("配置 Vue 静态资源:");
        if (hasFrontendService) {
            log.info(" 使用前端开发服务 (端口 {})", FRONTEND_PORT);
        } else if (StaticResourceLocator.hasFilesystemContent(StaticResourceLocator.DIST_FS)) {
            log.info("使用文件系统 dist");
        } else {
            log.info("使用 classpath:/dist/");
        }

        registry.addResourceHandler("/**")
                .addResourceLocations(
                        "file:src/main/resources/dist/",
                        "classpath:/dist/"
                )
                .setCachePeriod(0)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) {
                        try {
                            Resource requestedResource = location.createRelative(resourcePath);
                            if (requestedResource.exists() && requestedResource.isReadable()) {
                                return requestedResource;
                            }
                            // Vue SPA：无后缀路由回退到 index.html
                            if (!resourcePath.startsWith("api/") && !resourcePath.contains(".")) {
                                Resource indexResource = location.createRelative("index.html");
                                if (indexResource.exists() && indexResource.isReadable()) {
                                    return indexResource;
                                }
                            }
                        } catch (IOException e) {
                            // 返回 null
                        }
                        return null;
                    }
                });
    }

    private boolean detectFrontendService() {
        String[] hosts = {"127.0.0.1", "[::1]", "localhost"};

        for (String host : hosts) {
            try {
                String bareHost = host;
                if (bareHost.startsWith("[") && bareHost.endsWith("]")) {
                    bareHost = bareHost.substring(1, bareHost.length() - 1);
                }
                URI uri = new URI("http", null, bareHost, FRONTEND_PORT, "/", null, null);
                URL url = uri.toURL();
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                connection.setRequestMethod("GET");
                connection.setConnectTimeout(1000);
                connection.setReadTimeout(1000);
                connection.setInstanceFollowRedirects(false);

                int responseCode = connection.getResponseCode();
                connection.disconnect();

                if (responseCode >= 200 && responseCode < 500) {
                    return true;
                }
            } catch (Exception e) {
                // 继续尝试下一个地址
            }
        }

        return false;
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }
}
