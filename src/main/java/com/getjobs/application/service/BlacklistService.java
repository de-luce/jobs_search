package com.getjobs.application.service;

import com.getjobs.application.entity.BlacklistEntity;
import com.getjobs.application.mapper.BlacklistMapper;
import com.mybatisflex.core.query.QueryChain;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.getjobs.application.entity.table.BlacklistTableDef.BLACKLIST;

/**
 * 全局公司黑名单：公司名包含任一黑名单词条（LIKE）则跳过投递。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlacklistService {

    private final BlacklistMapper blacklistMapper;
    private final DataSource dataSource;

    /** 内存缓存，避免投递循环中频繁查库 */
    private final CopyOnWriteArrayList<String> companyCache = new CopyOnWriteArrayList<>();

    @PostConstruct
    public void init() {
        ensureTableExists();
        refreshCache();
    }

    public void ensureTableExists() {
        String ddl = "CREATE TABLE IF NOT EXISTS boss_blacklist (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "type VARCHAR(20) NOT NULL," +
                "value VARCHAR(200) NOT NULL," +
                "created_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
                "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP" +
                ")";
        String idx = "CREATE UNIQUE INDEX IF NOT EXISTS idx_blacklist_type_value ON boss_blacklist(type, value)";
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute(ddl);
            st.execute(idx);
        } catch (Exception e) {
            log.warn("确保 boss_blacklist 表存在失败: {}", e.getMessage());
        }
    }

    public synchronized void refreshCache() {
        List<BlacklistEntity> list = listByType(BlacklistEntity.TYPE_COMPANY);
        List<String> values = new ArrayList<>();
        for (BlacklistEntity e : list) {
            if (e.getValue() != null && !e.getValue().isBlank()) {
                values.add(e.getValue().trim());
            }
        }
        companyCache.clear();
        companyCache.addAll(values);
        log.debug("公司黑名单缓存已刷新，共 {} 条", companyCache.size());
    }

    public List<BlacklistEntity> listAll() {
        return QueryChain.of(blacklistMapper)
                .orderBy(BLACKLIST.ID.desc())
                .list();
    }

    public List<BlacklistEntity> listByType(String type) {
        return QueryChain.of(blacklistMapper)
                .where(BLACKLIST.TYPE.eq(type))
                .orderBy(BLACKLIST.ID.desc())
                .list();
    }

    public List<BlacklistEntity> listCompanies() {
        return listByType(BlacklistEntity.TYPE_COMPANY);
    }

    /**
     * 新增公司黑名单词条（去重）。
     */
    public BlacklistEntity addCompany(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("黑名单内容不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > 200) {
            throw new IllegalArgumentException("黑名单内容过长（最多200字符）");
        }
        BlacklistEntity existing = QueryChain.of(blacklistMapper)
                .where(BLACKLIST.TYPE.eq(BlacklistEntity.TYPE_COMPANY))
                .and(BLACKLIST.VALUE.eq(trimmed))
                .limit(1)
                .one();
        if (existing != null) {
            return existing;
        }
        BlacklistEntity entity = new BlacklistEntity();
        entity.setType(BlacklistEntity.TYPE_COMPANY);
        entity.setValue(trimmed);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        blacklistMapper.insert(entity);
        refreshCache();
        return entity;
    }

    public boolean deleteById(Long id) {
        if (id == null) return false;
        int rows = blacklistMapper.deleteById(id);
        if (rows > 0) {
            refreshCache();
            return true;
        }
        return false;
    }

    /**
     * 公司名是否命中黑名单（子串匹配，等同 SQL LIKE '%value%'）。
     */
    public boolean isCompanyBlacklisted(String companyName) {
        return findMatchedCompany(companyName) != null;
    }

    /**
     * 返回命中的黑名单词条；未命中返回 null。
     */
    public String findMatchedCompany(String companyName) {
        if (companyName == null || companyName.isBlank()) {
            return null;
        }
        if (companyCache.isEmpty()) {
            return null;
        }
        String name = companyName.trim().toLowerCase(Locale.ROOT);
        for (String entry : companyCache) {
            if (entry != null && !entry.isBlank()
                    && name.contains(entry.trim().toLowerCase(Locale.ROOT))) {
                return entry;
            }
        }
        return null;
    }
}
