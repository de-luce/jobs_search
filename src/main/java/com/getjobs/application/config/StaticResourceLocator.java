package com.getjobs.application.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 判定 Vue 前端静态资源：开发态看文件系统 dist，打包态看 classpath:/dist/。
 */
public final class StaticResourceLocator {

    public static final String DIST_FS = "src/main/resources/dist";

    private StaticResourceLocator() {
    }

    public static boolean hasStaticResources() {
        return hasFilesystemContent(DIST_FS) || hasClasspathResource("dist/index.html");
    }

    public static boolean hasFilesystemContent(String relativePath) {
        Path path = Paths.get(relativePath);
        try {
            if (!Files.exists(path) || !Files.isDirectory(path)) {
                return false;
            }
            try (var stream = Files.list(path)) {
                return stream.anyMatch(p -> {
                    String name = p.getFileName().toString();
                    return !name.startsWith(".");
                });
            }
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean hasClasspathResource(String classpathLocation) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = StaticResourceLocator.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(classpathLocation)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }
}
