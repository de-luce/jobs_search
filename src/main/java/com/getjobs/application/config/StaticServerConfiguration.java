package com.getjobs.application.config;

import org.apache.catalina.connector.Connector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/**
 * 静态资源服务器配置
 * 当前端 dev 服务未运行时，在 6866 端口提供 Vue 构建产物（resources/dist）
 */
@Slf4j
@Configuration
public class StaticServerConfiguration {
    private static final int FRONTEND_PORT = 6866;

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> servletContainer() {
        return server -> {
            boolean hasFrontendDev = detectFrontendDevServer();

            if (hasFrontendDev) {
                log.info("检测到前端开发服务运行在端口 {}", FRONTEND_PORT);
                return;
            }

            if (StaticResourceLocator.hasStaticResources()) {
                log.info("未检测到前端开发服务，但找到静态资源（文件系统或 classpath）");
                log.info("配置额外端口 {} 用于提供静态资源", FRONTEND_PORT);

                Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
                connector.setPort(FRONTEND_PORT);
                server.addAdditionalTomcatConnectors(connector);
            } else {
                log.warn("未检测到前端开发服务，也未找到静态资源");
            }
        };
    }

    private boolean detectFrontendDevServer() {
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
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(2000);
                connection.setInstanceFollowRedirects(false);

                int responseCode = connection.getResponseCode();
                connection.disconnect();

                if (responseCode >= 200 && responseCode < 500) {
                    return true;
                }
            } catch (Exception e) {
                log.debug("检测前端服务失败 ({}): {}", host, e.getMessage());
            }
        }

        log.debug("前端开发服务检测失败，已尝试所有地址");
        return false;
    }
}
