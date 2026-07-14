package com.getjobs.application.service;

import com.getjobs.application.entity.Job51ConfigEntity;
import com.getjobs.application.entity.Job51Entity;
import com.getjobs.application.entity.Job51OptionEntity;
import com.getjobs.application.mapper.Job51ConfigMapper;
import com.getjobs.application.mapper.Job51Mapper;
import com.getjobs.application.mapper.Job51OptionMapper;
import com.getjobs.application.utils.DeliveryStatuses;
import com.getjobs.worker.job51.Job51Config;
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
import java.util.stream.Collectors;

import static com.getjobs.application.entity.table.Job51ConfigTableDef.JOB51_CONFIG;
import static com.getjobs.application.entity.table.Job51OptionTableDef.JOB51_OPTION;
import static com.getjobs.application.entity.table.Job51TableDef.JOB51;

@Service
@Slf4j
@RequiredArgsConstructor
public class Job51Service {
    private final Job51ConfigMapper job51ConfigMapper;
    private final Job51OptionMapper job51OptionMapper;
    private final Job51Mapper job51Mapper;
    private final DataSource dataSource;
    private final BlacklistService blacklistService;

    /** 获取第一条配置（通常只有一条） */
    public Job51ConfigEntity getFirstConfig() {
        return QueryChain.of(job51ConfigMapper)
                .limit(1)
                .one();
    }

    /** 从专表构建 Job51Config */
    public Job51Config loadJob51Config() {
        Job51ConfigEntity entity = getFirstConfig();
        Job51Config config = new Job51Config();
        if (entity == null) {
            log.warn("job51_config 表为空，使用默认空配置");
            config.setKeywords(new ArrayList<>());
            config.setJobArea(new ArrayList<>());
            config.setSalary(new ArrayList<>());
            return config;
        }

        // 关键词解析
        config.setKeywords(parseListString(entity.getKeywords()));

        // 城市区域：中文名或代码列表 -> 统一为代码列表（优先使用数据库映射）
        List<String> areaInputs = parseListString(entity.getJobArea());
        List<String> areaCodes = new ArrayList<>();
        for (String input : areaInputs) {
            if (input == null || input.isEmpty()) continue;
            String code = normalizeOptionCode("jobArea", input);
            areaCodes.add(code);
        }
        config.setJobArea(areaCodes);

        // 薪资范围：中文名或代码列表 -> 统一为代码列表（优先使用数据库映射）
        List<String> salaryInputs = parseListString(entity.getSalary());
        List<String> salaryCodes = new ArrayList<>();
        for (String input : salaryInputs) {
            if (input == null || input.isEmpty()) continue;
            String code = normalizeOptionCode("salary", input);
            if (com.getjobs.worker.utils.Constant.UNLIMITED_CODE.equals(code)) continue;
            salaryCodes.add(code);
        }
        config.setSalary(salaryCodes);

        return config;
    }

    public List<String> parseListString(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<>();
        String s = raw.trim().replace('，', ',');

        // 尝试作为 JSON 数组解析
        if (s.startsWith("[") && s.endsWith("]")) {
            try {
                // 使用简单的 JSON 数组解析（处理带引号的字符串）
                String content = s.substring(1, s.length() - 1).trim();
                if (content.isEmpty()) return new ArrayList<>();

                List<String> result = new ArrayList<>();
                // 分割逗号，但要处理引号内的逗号
                boolean inQuotes = false;
                StringBuilder current = new StringBuilder();
                for (int i = 0; i < content.length(); i++) {
                    char c = content.charAt(i);
                    if (c == '"' && (i == 0 || content.charAt(i - 1) != '\\')) {
                        inQuotes = !inQuotes;
                    } else if (c == ',' && !inQuotes) {
                        String token = current.toString().trim();
                        if (!token.isEmpty()) {
                            // 去除首尾引号
                            if (token.startsWith("\"") && token.endsWith("\"")) {
                                token = token.substring(1, token.length() - 1);
                            }
                            // 处理转义的引号
                            token = token.replace("\\\"", "\"");
                            result.add(token);
                        }
                        current = new StringBuilder();
                    } else {
                        current.append(c);
                    }
                }
                // 处理最后一个元素
                String token = current.toString().trim();
                if (!token.isEmpty()) {
                    if (token.startsWith("\"") && token.endsWith("\"")) {
                        token = token.substring(1, token.length() - 1);
                    }
                    token = token.replace("\\\"", "\"");
                    result.add(token);
                }
                return result;
            } catch (Exception e) {
                log.warn("解析 JSON 数组失败，尝试简单分割: {}", e.getMessage());
            }
        }

        // 回退到简单的逗号分割（兼容旧格式）
        return java.util.Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(str -> !str.isEmpty())
                .collect(Collectors.toList());
    }

    // ==================== 选项相关 ====================

    /** 根据类型获取选项列表 */
    public List<Job51OptionEntity> getOptionsByType(String type) {
        return QueryChain.of(job51OptionMapper)
                .where(JOB51_OPTION.TYPE.eq(type))
                .orderBy(JOB51_OPTION.SORT_ORDER.asc(), JOB51_OPTION.ID.asc())
                .list();
    }

    /** 按类型和输入（代码或名称）归一化为代码 */
    public String normalizeOptionCode(String type, String input) {
        if (input == null || input.trim().isEmpty()) return "";
        String v = input.trim();
        // 先按code匹配
        Job51OptionEntity c = QueryChain.of(job51OptionMapper)
                .where(JOB51_OPTION.TYPE.eq(type))
                .and(JOB51_OPTION.CODE.eq(v))
                .limit(1)
                .one();
        if (c != null) return c.getCode();
        // 再按name匹配
        Job51OptionEntity n = QueryChain.of(job51OptionMapper)
                .where(JOB51_OPTION.TYPE.eq(type))
                .and(JOB51_OPTION.NAME.eq(v))
                .limit(1)
                .one();
        if (n != null) return n.getCode();
        // 不再使用枚举兜底，保留原值（可能已是代码）
        return v;
    }

    // ==================== 表初始化与数据导入 ====================

    @PostConstruct
    public void ensureJob51OptionTableAndData() {
        // 仅确保表存在；城市选项完全由数据库维护
        ensureJob51OptionTable();
        ensureJob51DataTable();
    }

    private void ensureJob51OptionTable() {
        String createSql = "CREATE TABLE IF NOT EXISTS job51_option (" +
                " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                " type VARCHAR(50)," +
                " name VARCHAR(100)," +
                " code VARCHAR(100)," +
                " sort_order INTEGER," +
                " created_at DATETIME," +
                " updated_at DATETIME" +
                ")";
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(createSql);
        } catch (Exception e) {
            log.warn("创建 job51_option 表失败: {}", e.getMessage());
        }
    }

    // 初始化逻辑移除：数据由外部迁移并在数据库维护，无需自动填充

    private void insertOption(String type, String name, String code, int sortOrder, LocalDateTime now) {
        try {
            Job51OptionEntity e = new Job51OptionEntity();
            e.setType(type);
            e.setName(name);
            e.setCode(code);
            e.setSortOrder(sortOrder);
            e.setCreatedAt(now);
            e.setUpdatedAt(now);
            job51OptionMapper.insert(e);
        } catch (Exception ex) {
            log.warn("写入选项失败 type={} name={} code={}: {}", type, name, code, ex.getMessage());
        }
    }

    /**
     * 选择性更新：若传入 ID 则按 ID 更新；否则更新第一条记录（不存在则插入）
     */
    public Job51ConfigEntity updateConfig(Job51ConfigEntity config) {
        if (config == null) return null;
        if (config.getId() != null) {
            // 设置更新时间
            config.setUpdatedAt(java.time.LocalDateTime.now());
            job51ConfigMapper.update(config);
            return job51ConfigMapper.selectOneById(config.getId());
        }
        return saveOrUpdateFirstSelective(config);
    }

    /**
     * 保存或选择性更新第一条记录（仅覆盖非空字段）
     */
    public Job51ConfigEntity saveOrUpdateFirstSelective(Job51ConfigEntity incoming) {
        Job51ConfigEntity first = getFirstConfig();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        if (first == null) {
            Job51ConfigEntity toInsert = new Job51ConfigEntity();
            toInsert.setKeywords(incoming.getKeywords());
            toInsert.setJobArea(incoming.getJobArea());
            toInsert.setSalary(incoming.getSalary());
            toInsert.setCreatedAt(now);
            toInsert.setUpdatedAt(now);
            job51ConfigMapper.insert(toInsert);
            return getFirstConfig();
        } else {
            Job51ConfigEntity toUpdate = new Job51ConfigEntity();
            toUpdate.setId(first.getId());
            // 仅覆盖非空字段
            if (incoming.getKeywords() != null) toUpdate.setKeywords(incoming.getKeywords());
            if (incoming.getJobArea() != null) toUpdate.setJobArea(incoming.getJobArea());
            if (incoming.getSalary() != null) toUpdate.setSalary(incoming.getSalary());
            toUpdate.setCreatedAt(first.getCreatedAt());
            toUpdate.setUpdatedAt(now);
            job51ConfigMapper.update(toUpdate);
            return job51ConfigMapper.selectOneById(first.getId());
        }
    }

    // ==================== 51job 岗位数据表与持久化 ====================

    /** 创建 job51_data 表（如不存在） */
    private void ensureJob51DataTable() {
        String createSql = "CREATE TABLE IF NOT EXISTS job51_data (" +
                " job_id            BIGINT PRIMARY KEY," +
                " job_title         VARCHAR(200)," +
                " job_link          VARCHAR(300)," +
                " job_salary_text   VARCHAR(100)," +
                " job_area          VARCHAR(100)," +
                " job_edu_req       VARCHAR(50)," +
                " job_exp_req       VARCHAR(50)," +
                " job_publish_time  VARCHAR(50)," +
                " comp_id           BIGINT," +
                " comp_name         VARCHAR(200)," +
                " comp_industry     VARCHAR(100)," +
                " comp_scale        VARCHAR(50)," +
                " hr_id             VARCHAR(64)," +
                " hr_name           VARCHAR(50)," +
                " hr_title          VARCHAR(100)," +
                " delivery_status   VARCHAR(20) DEFAULT '未投递'," +
                " create_time       TEXT," +
                " update_time       TEXT" +
                ")";
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(createSql);
            try { stmt.execute("ALTER TABLE job51_data ADD COLUMN delivery_status VARCHAR(20) DEFAULT '未投递'"); } catch (Exception ignored) {}
            migrateLegacyDeliveredColumn(stmt, "job51_data");
            try { stmt.execute("ALTER TABLE job51_data DROP COLUMN account_id"); } catch (Exception ignored) {}
            log.info("确保 job51_data 表已存在");
        } catch (Exception e) {
            log.warn("创建 job51_data 表失败: {}", e.getMessage());
        }
    }

    private void migrateLegacyDeliveredColumn(Statement stmt, String table) {
        try {
            boolean hasDelivered = false;
            try (var rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
                while (rs.next()) {
                    if ("delivered".equalsIgnoreCase(rs.getString("name"))) {
                        hasDelivered = true;
                        break;
                    }
                }
            }
            if (!hasDelivered) return;
            stmt.execute("UPDATE " + table + " SET delivery_status='未投递' WHERE delivered IS NULL OR delivered=0");
            stmt.execute("UPDATE " + table + " SET delivery_status='已投递' WHERE delivered=1");
            stmt.execute("UPDATE " + table + " SET delivery_status='已过滤' WHERE delivered=2");
            stmt.execute("UPDATE " + table + " SET delivery_status='未投递' WHERE delivery_status IS NULL OR delivery_status=''");
            try { stmt.execute("ALTER TABLE " + table + " DROP COLUMN delivered"); } catch (Exception ignored) {}
            log.info("{} 已从 delivered 迁移到 delivery_status", table);
        } catch (Exception e) {
            log.debug("迁移 {} delivered 列跳过: {}", table, e.getMessage());
        }
    }

    /** 批量插入（仅不存在时），默认未投递 */
    public void batchInsertIfNotExists(List<Job51Entity> entities) {
        if (entities == null || entities.isEmpty()) return;

        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (Job51Entity e : entities) {
            if (e != null && e.getJobId() != null) ids.add(e.getJobId());
        }
        if (ids.isEmpty()) return;

        java.util.List<Long> idList = new java.util.ArrayList<>(ids);
        java.util.List<Job51Entity> existing = QueryChain.of(job51Mapper)
                .where(JOB51.JOB_ID.in(idList))
                .list();
        java.util.Set<Long> existingIds = new java.util.HashSet<>();
        if (existing != null) {
            for (Job51Entity e : existing) {
                if (e != null && e.getJobId() != null) existingIds.add(e.getJobId());
            }
        }

        java.util.List<Job51Entity> toInsert = new java.util.ArrayList<>();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String nowIso = now.toString();
        for (Job51Entity e : entities) {
            if (e == null || e.getJobId() == null) continue;
            if (existingIds.contains(e.getJobId())) continue;
            if (e.getCreateTime() == null) e.setCreateTime(nowIso);
            e.setUpdateTime(nowIso);
            if (e.getDeliveryStatus() == null) e.setDeliveryStatus(DeliveryStatuses.PENDING);
            toInsert.add(e);
        }
        if (toInsert.isEmpty()) return;

        String sql = "INSERT INTO job51_data (" +
                "job_id, job_title, job_link, job_salary_text, job_area, job_edu_req, job_exp_req, job_publish_time, " +
                "comp_id, comp_name, comp_industry, comp_scale, " +
                "hr_id, hr_name, hr_title, delivery_status, create_time, update_time" +
                ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection conn = dataSource.getConnection(); java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            for (Job51Entity e : toInsert) {
                if (e.getJobId() == null) ps.setNull(1, java.sql.Types.BIGINT); else ps.setLong(1, e.getJobId());
                if (e.getJobTitle() == null) ps.setNull(2, java.sql.Types.VARCHAR); else ps.setString(2, e.getJobTitle());
                if (e.getJobLink() == null) ps.setNull(3, java.sql.Types.VARCHAR); else ps.setString(3, e.getJobLink());
                if (e.getJobSalaryText() == null) ps.setNull(4, java.sql.Types.VARCHAR); else ps.setString(4, e.getJobSalaryText());
                if (e.getJobArea() == null) ps.setNull(5, java.sql.Types.VARCHAR); else ps.setString(5, e.getJobArea());
                if (e.getJobEduReq() == null) ps.setNull(6, java.sql.Types.VARCHAR); else ps.setString(6, e.getJobEduReq());
                if (e.getJobExpReq() == null) ps.setNull(7, java.sql.Types.VARCHAR); else ps.setString(7, e.getJobExpReq());
                if (e.getJobPublishTime() == null) ps.setNull(8, java.sql.Types.VARCHAR); else ps.setString(8, e.getJobPublishTime());
                if (e.getCompId() == null) ps.setNull(9, java.sql.Types.BIGINT); else ps.setLong(9, e.getCompId());
                if (e.getCompName() == null) ps.setNull(10, java.sql.Types.VARCHAR); else ps.setString(10, e.getCompName());
                if (e.getCompIndustry() == null) ps.setNull(11, java.sql.Types.VARCHAR); else ps.setString(11, e.getCompIndustry());
                if (e.getCompScale() == null) ps.setNull(12, java.sql.Types.VARCHAR); else ps.setString(12, e.getCompScale());
                if (e.getHrId() == null) ps.setNull(13, java.sql.Types.VARCHAR); else ps.setString(13, e.getHrId());
                if (e.getHrName() == null) ps.setNull(14, java.sql.Types.VARCHAR); else ps.setString(14, e.getHrName());
                if (e.getHrTitle() == null) ps.setNull(15, java.sql.Types.VARCHAR); else ps.setString(15, e.getHrTitle());
                ps.setString(16, DeliveryStatuses.normalize(e.getDeliveryStatus()));
                if (e.getCreateTime() == null) ps.setNull(17, java.sql.Types.VARCHAR); else ps.setString(17, e.getCreateTime());
                if (e.getUpdateTime() == null) ps.setNull(18, java.sql.Types.VARCHAR); else ps.setString(18, e.getUpdateTime());
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (Exception e) {
            log.warn("批量插入 51job 岗位快照失败: {}", e.getMessage());
        }
    }

    /** 解析 51job 搜索接口返回 JSON，并批量保存 */
    public void parseAndPersistJob51SearchJson(String json) {
        if (json == null || json.isEmpty()) return;
        String trimmed = json.trim();
        // 非JSON：可能是访问验证页面，直接跳过并打印诊断
        if (trimmed.startsWith("<")) {
            // 静默跳过非JSON（可能是访问验证页面）
            return;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);

            // 兼容多种列表命名，新增 resultbody.job.items（根据现场日志）
            com.fasterxml.jackson.databind.JsonNode list = root.path("data").path("items");
            if (!list.isArray()) list = root.path("data").path("jobList");
            if (!list.isArray()) list = root.path("data").path("list");
            if (!list.isArray()) list = root.path("data").path("jobs");
            if (!list.isArray()) list = root.path("resultbody").path("job").path("items");
            if (!list.isArray()) list = root.path("job").path("items");
            if (!list.isArray()) list = root.path("resultbody").path("items");
            if (!list.isArray()) {
                // 静默跳过不兼容结构
                return;
            }

            java.util.List<Job51Entity> entities = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode item : list) {
                Long jobId = readLong(item.path("jobId"));
                if (jobId == null) continue;

                Job51Entity e = new Job51Entity();
                e.setJobId(jobId);
                // 名称/薪资/地区/学历/经验/发布时间 映射增强
                e.setJobTitle(readText(item, "jobName", "jobTitle", "title"));
                e.setJobSalaryText(readText(item, "provideSalaryString", "salaryDesc", "salary", "salaryText"));
                e.setJobArea(readText(item, "jobAreaString", "jobArea", "cityName"));
                e.setJobEduReq(readText(item, "degreeString", "degree", "requireEduLevel"));
                e.setJobExpReq(readText(item, "workYearString", "workYear", "requireWorkYears"));
                e.setJobPublishTime(readText(item, "issueDateString", "issueDate", "updateDate", "refreshTime"));

                // 公司维度
                e.setCompId(readLong(item.path("ctmId"), item.path("companyId")));
                e.setCompName(readText(item, "fullCompanyName", "companyName", "ctmName"));
                e.setCompIndustry(readText(item, "industryType1Str", "industry", "compIndustry"));
                e.setCompScale(readText(item, "companySizeString", "companySize", "compScale"));

                // HR维度
                e.setHrId(readText(item, "hrUid", "recruiterId"));
                e.setHrName(readText(item, "hrName", "recruiterName"));
                e.setHrTitle(readText(item, "hrPosition", "recruiterTitle"));

                // 职位链接：优先使用官方给出的 jobHref，其次构造 PC 链接
                String jobHref = readText(item, "jobHref");
                if (jobHref == null || jobHref.isEmpty()) {
                    jobHref = "https://we.51job.com/pc/jobdetail?jobId=" + jobId;
                }
                e.setJobLink(jobHref);

                if (blacklistService.isCompanyBlacklisted(e.getCompName())) {
                    e.setDeliveryStatus(DeliveryStatuses.FILTERED);
                } else {
                    e.setDeliveryStatus(DeliveryStatuses.PENDING);
                }

                entities.add(e);
            }
            batchInsertIfNotExists(entities);
            // 静默写库
        } catch (Exception e) {
            // 静默错误
        }
    }

    // 读取辅助
    private static String readText(com.fasterxml.jackson.databind.JsonNode item, String... keys) {
        for (String k : keys) {
            String v = safeText(item.path(k));
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    private static String safeText(com.fasterxml.jackson.databind.JsonNode node) {
        try { String v = node.asText(null); return (v == null || v.equals("null")) ? null : v; } catch (Exception ignored) { return null; }
    }

    private static Long readLong(com.fasterxml.jackson.databind.JsonNode... nodes) {
        for (com.fasterxml.jackson.databind.JsonNode n : nodes) {
            try {
                if (n == null) continue;
                String v = n.asText(null);
                if (v != null && !v.isEmpty() && !"null".equalsIgnoreCase(v)) {
                    return Long.parseLong(v);
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ==================== 投递状态写回 ====================

    /** 将指定 jobId 标记为已投递 */
    public void markDelivered(Long jobId) {
        if (jobId == null) return;
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "UPDATE job51_data SET delivery_status=?, update_time=? WHERE job_id=?")) {
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            ps.setString(1, DeliveryStatuses.DELIVERED);
            ps.setString(2, now.toString());
            ps.setLong(3, jobId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("标记 51job 已投递失败 job_id={}: {}", jobId, e.getMessage());
        }
    }

    /** 标记为已过滤（仅未投递时更新，避免覆盖已投递） */
    public void markFiltered(Long jobId) {
        if (jobId == null) return;
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "UPDATE job51_data SET delivery_status=?, update_time=? WHERE job_id=? AND (delivery_status IS NULL OR delivery_status=? OR delivery_status='')")) {
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            ps.setString(1, DeliveryStatuses.FILTERED);
            ps.setString(2, now.toString());
            ps.setLong(3, jobId);
            ps.setString(4, DeliveryStatuses.PENDING);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("标记 51job 已过滤失败 job_id={}: {}", jobId, e.getMessage());
        }
    }

    /** 批量标记为已过滤 */
    public void markFilteredBatch(java.util.Collection<Long> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) return;
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "UPDATE job51_data SET delivery_status=?, update_time=? WHERE job_id=? AND (delivery_status IS NULL OR delivery_status=? OR delivery_status='')")) {
            conn.setAutoCommit(false);
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            for (Long id : jobIds) {
                if (id == null) continue;
                ps.setString(1, DeliveryStatuses.FILTERED);
                ps.setString(2, now.toString());
                ps.setLong(3, id);
                ps.setString(4, DeliveryStatuses.PENDING);
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (Exception e) {
            log.warn("批量标记 51job 已过滤失败: {}", e.getMessage());
        }
    }

    /** 批量标记为已投递 */
    public void markDeliveredBatch(java.util.Collection<Long> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) return;
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "UPDATE job51_data SET delivery_status=?, update_time=? WHERE job_id=?")) {
            conn.setAutoCommit(false);
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            for (Long id : jobIds) {
                if (id == null) continue;
                ps.setString(1, DeliveryStatuses.DELIVERED);
                ps.setString(2, now.toString());
                ps.setLong(3, id);
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            conn.commit();
            try {
                int updated = 0;
                if (counts != null) {
                    for (int c : counts) {
                        if (c > 0) updated += c;
                    }
                }
                String sample = jobIds.stream().filter(java.util.Objects::nonNull).limit(5).map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
                log.info("[51job] 批量标记已投递完成，入参 {} 条，成功更新 {} 条，示例ID: {}", jobIds.size(), updated, sample);
            } catch (Exception ignored) {}
        } catch (Exception e) {
            log.warn("批量标记 51job 已投递失败: {}", e.getMessage());
        }
    }

    // ==================== 投递分析与列表 ====================

    public static class NameValue { public String name; public long value; public NameValue() {} public NameValue(String name, long value) { this.name = name; this.value = value; } }
    public static class BucketValue { public String bucket; public long value; public BucketValue() {} public BucketValue(String bucket, long value) { this.bucket = bucket; this.value = value; } }
    public static class Charts {
        public java.util.List<NameValue> byStatus;
        public java.util.List<NameValue> byCity;
        public java.util.List<NameValue> byIndustry;
        public java.util.List<NameValue> byCompany;
        public java.util.List<NameValue> byExperience;
        public java.util.List<NameValue> byDegree;
        public java.util.List<BucketValue> salaryBuckets;
        public java.util.List<NameValue> dailyTrend; // date as name
    }
    public static class Kpi { public long total; public long delivered; public long pending; public long filtered; public long failed; public Double avgMonthlyK; }
    public static class StatsResponse { public Kpi kpi; public Charts charts; }

    public static class Job51Row {
        public Long jobId;
        public String companyName;
        public String jobName;
        public String salary;
        public String location;
        public String experience;
        public String degree;
        public String hrName;
        public String deliveryStatus; // 已投递/未投递/已过滤
        public String jobUrl;
        public String publishTime;
        public String createdAt;
        public String industry;
        public String companyScale;
    }
    public static class PagedResult51 {
        public java.util.List<Job51Row> items;
        public long total;
        public int page;
        public int size;
    }

    /** 获取 51job 投递分析统计与图表数据（按筛选条件） */
    public StatsResponse getJob51Stats(
            java.util.List<String> statuses,
            String location,
            String experience,
            String degree,
            Double minK,
            Double maxK,
            String keyword
    ) {
        StatsResponse resp = new StatsResponse();
        resp.kpi = new Kpi();
        Charts charts = new Charts();
        charts.byStatus = new java.util.ArrayList<>();
        charts.byCity = new java.util.ArrayList<>();
        charts.byIndustry = new java.util.ArrayList<>();
        charts.byCompany = new java.util.ArrayList<>();
        charts.byExperience = new java.util.ArrayList<>();
        charts.byDegree = new java.util.ArrayList<>();
        charts.salaryBuckets = new java.util.ArrayList<>();
        charts.dailyTrend = new java.util.ArrayList<>();

        try {
            QueryChain<Job51Entity> wrapper = QueryChain.of(job51Mapper);
            if (statuses != null && !statuses.isEmpty()) {
                java.util.Set<String> statusSet = statuses.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(java.util.stream.Collectors.toSet());
                if (!statusSet.isEmpty()) wrapper.where(JOB51.DELIVERY_STATUS.in(statusSet));
            }
            if (location != null && !location.trim().isEmpty()) wrapper.and(JOB51.JOB_AREA.eq(location.trim()));
            if (experience != null && !experience.trim().isEmpty()) wrapper.and(JOB51.JOB_EXP_REQ.eq(experience.trim()));
            if (degree != null && !degree.trim().isEmpty()) wrapper.and(JOB51.JOB_EDU_REQ.eq(degree.trim()));
            if (keyword != null && !keyword.trim().isEmpty()) {
                String kw = keyword.trim();
                wrapper.and(JOB51.COMP_NAME.like(kw)
                        .or(JOB51.JOB_TITLE.like(kw))
                        .or(JOB51.HR_NAME.like(kw)));
            }
            wrapper.orderBy(JOB51.UPDATE_TIME.desc());

            java.util.List<Job51Entity> all = wrapper.list();

            // 薪资区间过滤与中位数计算
            java.util.List<Job51Entity> filtered = new java.util.ArrayList<>();
            double sumMedian = 0.0; long countMedian = 0;
            java.util.List<Double> medians = new java.util.ArrayList<>();
            for (Job51Entity e : all) {
                SalaryInfo info = parse51Salary(e.getJobSalaryText());
                boolean passSalary;
                if (minK == null && maxK == null) passSalary = true;
                else {
                    if (info == null || info.medianK == null) passSalary = false;
                    else {
                        boolean ok = true;
                        if (minK != null) ok &= (info.medianK >= minK);
                        if (maxK != null) ok &= (info.medianK <= maxK);
                        passSalary = ok;
                    }
                }
                if (passSalary) {
                    filtered.add(e);
                    if (info != null && info.medianK != null) { sumMedian += info.medianK; countMedian++; medians.add(info.medianK); }
                }
            }

            // KPI
            resp.kpi.total = filtered.size();
            resp.kpi.delivered = filtered.stream().filter(e -> DeliveryStatuses.DELIVERED.equals(DeliveryStatuses.normalize(e.getDeliveryStatus()))).count();
            resp.kpi.pending = filtered.stream().filter(e -> DeliveryStatuses.PENDING.equals(DeliveryStatuses.normalize(e.getDeliveryStatus()))).count();
            resp.kpi.filtered = filtered.stream().filter(e -> DeliveryStatuses.FILTERED.equals(DeliveryStatuses.normalize(e.getDeliveryStatus()))).count();
            resp.kpi.failed = filtered.stream().filter(e -> DeliveryStatuses.FAILED.equals(DeliveryStatuses.normalize(e.getDeliveryStatus()))).count();
            resp.kpi.avgMonthlyK = countMedian > 0 ? Math.round((sumMedian / countMedian) * 100.0) / 100.0 : null;

            // Charts
            java.util.Map<String, Long> byStatus = filtered.stream()
                    .collect(java.util.stream.Collectors.groupingBy(e -> DeliveryStatuses.normalize(e.getDeliveryStatus()), java.util.stream.Collectors.counting()));
            byStatus.forEach((k,v) -> charts.byStatus.add(new NameValue(nullSafe(k), v)));

            java.util.Map<String, Long> byCity = filtered.stream()
                    .collect(java.util.stream.Collectors.groupingBy(e -> nullSafe(e.getJobArea()), java.util.stream.Collectors.counting()));
            byCity.entrySet().stream().sorted((a,b)->Long.compare(b.getValue(), a.getValue())).limit(10).forEach(en -> charts.byCity.add(new NameValue(en.getKey(), en.getValue())));

            java.util.Map<String, Long> byIndustry = filtered.stream()
                    .collect(java.util.stream.Collectors.groupingBy(e -> nullSafe(e.getCompIndustry()), java.util.stream.Collectors.counting()));
            byIndustry.entrySet().stream().sorted((a,b)->Long.compare(b.getValue(), a.getValue())).limit(10).forEach(en -> charts.byIndustry.add(new NameValue(en.getKey(), en.getValue())));

            java.util.Map<String, Long> byCompany = filtered.stream()
                    .collect(java.util.stream.Collectors.groupingBy(e -> nullSafe(e.getCompName()), java.util.stream.Collectors.counting()));
            byCompany.entrySet().stream().sorted((a,b)->Long.compare(b.getValue(), a.getValue())).limit(10).forEach(en -> charts.byCompany.add(new NameValue(en.getKey(), en.getValue())));

            java.util.Map<String, Long> byExp = filtered.stream()
                    .collect(java.util.stream.Collectors.groupingBy(e -> nullSafe(e.getJobExpReq()), java.util.stream.Collectors.counting()));
            byExp.forEach((k,v) -> charts.byExperience.add(new NameValue(k,v)));

            java.util.Map<String, Long> byDeg = filtered.stream()
                    .collect(java.util.stream.Collectors.groupingBy(e -> nullSafe(e.getJobEduReq()), java.util.stream.Collectors.counting()));
            byDeg.forEach((k,v) -> charts.byDegree.add(new NameValue(k,v)));

            java.util.Map<String, Long> byDay = filtered.stream()
                    .collect(java.util.stream.Collectors.groupingBy(e -> {
                        String t = e.getCreateTime();
                        if (t == null || t.length() < 10) return "未知";
                        return t.substring(0,10);
                    }, java.util.stream.Collectors.counting()));
            byDay.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(en -> charts.dailyTrend.add(new NameValue(en.getKey(), en.getValue())));

            // salaryBuckets 动态上限
            long b0_10=0,b10_15=0,b15_20=0,b20_top=0,b_ge_top=0;
            double maxMedian = 0.0;
            for (double m : medians) { if (m > maxMedian) maxMedian = m; }
            int topEdge = (int) Math.ceil(maxMedian / 5.0) * 5; if (topEdge <= 20) topEdge = 25;
            for (double m : medians) {
                if (m < 10) b0_10++;
                else if (m < 15) b10_15++;
                else if (m < 20) b15_20++;
                else if (m < topEdge) b20_top++;
                else b_ge_top++;
            }
            charts.salaryBuckets.add(new BucketValue("0-10K", b0_10));
            charts.salaryBuckets.add(new BucketValue("10-15K", b10_15));
            charts.salaryBuckets.add(new BucketValue("15-20K", b15_20));
            charts.salaryBuckets.add(new BucketValue("20-" + topEdge + "K", b20_top));
            charts.salaryBuckets.add(new BucketValue(">=" + topEdge + "K", b_ge_top));

            resp.charts = charts;
            return resp;
        } catch (Exception e) {
            resp.charts = charts;
            return resp;
        }
    }

    /** 列表查询（分页 + 筛选 + 关键词 + 薪资区间基于中位数K） */
    public PagedResult51 listJob51(
            java.util.List<String> statuses,
            String location,
            String experience,
            String degree,
            Double minK,
            Double maxK,
            String keyword,
            int page,
            int size
    ) {
        if (page <= 0) page = 1;
        if (size <= 0) size = 20;

        QueryChain<Job51Entity> wrapper = QueryChain.of(job51Mapper);
        if (statuses != null && !statuses.isEmpty()) {
            java.util.Set<String> statusSet = statuses.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.toSet());
            if (!statusSet.isEmpty()) wrapper.where(JOB51.DELIVERY_STATUS.in(statusSet));
        }
        if (location != null && !location.trim().isEmpty()) wrapper.and(JOB51.JOB_AREA.eq(location.trim()));
        if (experience != null && !experience.trim().isEmpty()) wrapper.and(JOB51.JOB_EXP_REQ.eq(experience.trim()));
        if (degree != null && !degree.trim().isEmpty()) wrapper.and(JOB51.JOB_EDU_REQ.eq(degree.trim()));
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(JOB51.COMP_NAME.like(kw)
                    .or(JOB51.JOB_TITLE.like(kw))
                    .or(JOB51.HR_NAME.like(kw)));
        }
        wrapper.orderBy(JOB51.UPDATE_TIME.desc());

        java.util.List<Job51Entity> all = wrapper.list();

        java.util.List<Job51Entity> filtered = new java.util.ArrayList<>();
        for (Job51Entity e : all) {
            if (minK == null && maxK == null) { filtered.add(e); }
            else {
                SalaryInfo info = parse51Salary(e.getJobSalaryText());
                if (info == null || info.medianK == null) continue;
                boolean ok = true;
                if (minK != null) ok &= (info.medianK >= minK);
                if (maxK != null) ok &= (info.medianK <= maxK);
                if (ok) filtered.add(e);
            }
        }

        int total = filtered.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(total, from + size);
        java.util.List<Job51Entity> pageItems = from >= to ? java.util.Collections.emptyList() : filtered.subList(from, to);

        java.util.List<Job51Row> rows = new java.util.ArrayList<>();
        for (Job51Entity e : pageItems) {
            Job51Row r = new Job51Row();
            r.jobId = e.getJobId();
            r.companyName = e.getCompName();
            r.jobName = e.getJobTitle();
            r.salary = e.getJobSalaryText();
            r.location = e.getJobArea();
            r.experience = e.getJobExpReq();
            r.degree = e.getJobEduReq();
            r.hrName = e.getHrName();
            r.deliveryStatus = DeliveryStatuses.normalize(e.getDeliveryStatus());
            r.jobUrl = e.getJobLink();
            r.publishTime = e.getJobPublishTime();
            r.createdAt = e.getCreateTime();
            r.industry = e.getCompIndustry();
            r.companyScale = e.getCompScale();
            rows.add(r);
        }

        PagedResult51 result = new PagedResult51();
        result.items = rows;
        result.total = total;
        result.page = page;
        result.size = size;
        return result;
    }

    // ==================== 薪资解析 ====================
    private static class SalaryInfo { Double medianK; }
    private SalaryInfo parse51Salary(String salaryText) {
        if (salaryText == null) return null;
        String s = salaryText.trim().toLowerCase();
        if (s.isEmpty() || s.contains("面议")) return null;
        // 提取数字范围（支持小数）
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\s*[-~]\s*(\\d+(?:\\.\\d+)?)").matcher(s);
        Double a = null, b = null;
        if (m.find()) {
            a = Double.valueOf(m.group(1));
            b = Double.valueOf(m.group(2));
        } else {
            java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(s);
            if (m2.find()) {
                a = Double.valueOf(m2.group(1)); b = a;
            }
        }
        if (a == null || b == null) return null;
        double min = Math.min(a, b), max = Math.max(a, b);
        double factorK = 1.0; // 数值单位到K
        // 单位判断
        if (s.contains("k")) factorK = 1.0;
        else if (s.contains("千") && s.contains("/月")) factorK = 1.0;
        else if (s.contains("万") && s.contains("/月")) factorK = 10.0;
        else if (s.contains("万") && (s.contains("/年") || s.contains("年"))) factorK = 10.0 / 12.0;
        else if (s.contains("元/天")) {
            // 粗略换算：按22个工作日，每天X元 -> 月K
            factorK = (1.0 / 1000.0) * 22.0;
        }
        double medianK = ((min + max) / 2.0) * factorK;
        SalaryInfo info = new SalaryInfo(); info.medianK = medianK; return info;
    }

    private String nullSafe(String s) { return (s == null || s.isEmpty()) ? "未知" : s; }

    /** 刷新 51job 数据：执行 VACUUM 并返回当前总数 */
    public java.util.Map<String, Object> reloadJob51Data() {
        java.util.Map<String, Object> resp = new java.util.HashMap<>();
        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            try (Statement st = conn.createStatement()) {
                try { st.execute("PRAGMA wal_checkpoint(TRUNCATE)"); } catch (Exception ignore) {}
                try { st.execute("VACUUM"); } catch (Exception ignore) {}
            }
            long total = scalarCount(conn, "SELECT COUNT(*) FROM job51_data");
            resp.put("success", true);
            resp.put("message", "刷新完成");
            resp.put("total", total);
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("message", "刷新失败: " + e.getMessage());
        } finally { try { if (conn != null) conn.close(); } catch (Exception ignore) {} }
        return resp;
    }

    private long scalarCount(Connection conn, String sql) throws Exception {
        try (java.sql.Statement st = conn.createStatement(); java.sql.ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }
}