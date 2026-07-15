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
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.getjobs.application.entity.table.BlacklistTableDef.BLACKLIST;

/**
 * 全局公司黑名单：规范化后做子串双向匹配（忽略大小写/空白/常见括号，并弱化「有限公司」等后缀差异）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlacklistService {

    private static final int MIN_BIDIRECTIONAL_LEN = 2;

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
     * 公司名是否命中黑名单。
     */
    public boolean isCompanyBlacklisted(String companyName) {
        return findMatchedCompany(companyName) != null;
    }

    /**
     * 任一候选公司名命中即返回词条；用于同一岗位同时有品牌名/全称等字段。
     */
    public String findMatchedCompanyAny(Collection<String> companyNames) {
        if (companyNames == null || companyNames.isEmpty()) {
            return null;
        }
        for (String name : companyNames) {
            String matched = findMatchedCompany(name);
            if (matched != null) {
                return matched;
            }
        }
        return null;
    }

    public String findMatchedCompanyAny(String... companyNames) {
        if (companyNames == null || companyNames.length == 0) {
            return null;
        }
        for (String name : companyNames) {
            String matched = findMatchedCompany(name);
            if (matched != null) {
                return matched;
            }
        }
        return null;
    }

    /**
     * 返回命中的黑名单词条；未命中返回 null。
     * 占位文案（未知公司/公司）不参与匹配，避免假阴性被当成真公司。
     */
    public String findMatchedCompany(String companyName) {
        if (companyName == null || companyName.isBlank() || isPlaceholderCompany(companyName)) {
            return null;
        }
        if (companyCache.isEmpty()) {
            return null;
        }
        String nameNorm = normalizeCompany(companyName);
        if (nameNorm.isEmpty()) {
            return null;
        }
        String nameCore = stripLegalSuffix(nameNorm);
        for (String entry : companyCache) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String entryNorm = normalizeCompany(entry);
            if (entryNorm.isEmpty()) {
                continue;
            }
            String entryCore = stripLegalSuffix(entryNorm);
            if (matchesNormalized(nameNorm, entryNorm) || matchesNormalized(nameCore, entryCore)) {
                return entry;
            }
        }
        return null;
    }

    /** 无法用于黑名单校验的占位文案 */
    public static boolean isPlaceholderCompany(String companyName) {
        if (companyName == null || companyName.isBlank()) {
            return true;
        }
        String t = companyName.trim();
        return "公司".equals(t)
                || "未知公司".equals(t)
                || "未知".equals(t)
                || "-".equals(t)
                || "—".equals(t);
    }

    /**
     * 规范化：小写、去空白、统一括号/标点；便于 DOM 换行名与配置关键词对齐。
     */
    static String normalizeCompany(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.toLowerCase(Locale.ROOT)
                .replace('\u00a0', ' ')
                .replaceAll("\\s+", "")
                .replace('（', '(')
                .replace('）', ')')
                .replace('【', '[')
                .replace('】', ']')
                .replace('「', '[')
                .replace('」', ']')
                .replace("・", "")
                .replace("·", "")
                .replace(".", "")
                .replace("。", "")
                .replace(",", "")
                .replace("，", "");
        return s;
    }

    /** 去掉常见法律后缀，降低「腾讯」vs「腾讯科技有限公司」漏匹配 */
    static String stripLegalSuffix(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return "";
        }
        String s = normalized;
        String[] suffixes = {
                "股份有限公司", "有限责任公司", "有限公司", "集团有限公司",
                "集团", "公司", "株式会社", "inc", "ltd", "co", "corp", "llc"
        };
        boolean changed;
        do {
            changed = false;
            for (String suffix : suffixes) {
                if (s.endsWith(suffix) && s.length() > suffix.length() + 1) {
                    s = s.substring(0, s.length() - suffix.length());
                    changed = true;
                }
            }
        } while (changed);
        return s;
    }

    private static boolean matchesNormalized(String name, String entry) {
        if (name.isEmpty() || entry.isEmpty()) {
            return false;
        }
        if (name.contains(entry)) {
            return true;
        }
        // 词条更长时（用户填了全称、页面只有品牌名）允许反向包含，但要求双方不太短
        return entry.length() >= MIN_BIDIRECTIONAL_LEN
                && name.length() >= MIN_BIDIRECTIONAL_LEN
                && entry.contains(name);
    }
}
