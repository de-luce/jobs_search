package com.getjobs.application.init;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 在 Spring 启动前确保 SQLite 文件存在：缺失时用 classpath 中的 schema 建库。
 */
public final class DatabaseBootstrap {

    private static final Logger log = Logger.getLogger(DatabaseBootstrap.class.getName());
    private static final Path DB_DIR = Paths.get("db");
    private static final Path DB_FILE = DB_DIR.resolve("getjobs.db");
    private static final String JDBC_URL = "jdbc:sqlite:" + DB_FILE.toString().replace('\\', '/');

    private DatabaseBootstrap() {
    }

    public static void ensureDatabase() {
        try {
            if (Files.exists(DB_FILE) && Files.size(DB_FILE) > 0) {
                log.info("SQLite 已存在: " + DB_FILE.toAbsolutePath());
                return;
            }
            Files.createDirectories(DB_DIR);
            Class.forName("org.sqlite.JDBC");
            ClassPathResource schema = new ClassPathResource("db/schema.sql");
            if (!schema.exists()) {
                throw new IllegalStateException("classpath 缺少 db/schema.sql，无法初始化数据库");
            }
            try (Connection conn = DriverManager.getConnection(JDBC_URL)) {
                ScriptUtils.executeSqlScript(conn, schema);
            }
            log.info("已初始化空库: " + DB_FILE.toAbsolutePath());
        } catch (Exception e) {
            log.log(Level.SEVERE, "初始化数据库失败: " + e.getMessage(), e);
            throw new IllegalStateException("初始化数据库失败: " + e.getMessage(), e);
        }
    }
}
