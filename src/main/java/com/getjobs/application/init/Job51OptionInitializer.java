package com.getjobs.application.init;

import com.getjobs.application.entity.Job51OptionEntity;
import com.getjobs.application.mapper.Job51OptionMapper;
import com.mybatisflex.core.query.QueryChain;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;

import static com.getjobs.application.entity.table.Job51OptionTableDef.JOB51_OPTION;

/**
 * 启动时确保 job51_option / job51_config 列完整，并写入官网搜索筛选项字典。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Job51OptionInitializer implements CommandLineRunner {

    private final DataSource dataSource;
    private final Job51OptionMapper job51OptionMapper;

    @Override
    public void run(String... args) {
        ensureTableExists();
        ensureConfigColumns();
        seedOptions();
    }

    private void ensureConfigColumns() {
        String[] columns = {
                "work_year VARCHAR(50)",
                "degree VARCHAR(50)",
                "company_type VARCHAR(50)",
                "company_size VARCHAR(50)",
                "job_type VARCHAR(50)"
        };
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            for (String col : columns) {
                try {
                    stmt.execute("ALTER TABLE job51_config ADD COLUMN " + col);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.warn("扩展 job51_config 表失败: {}", e.getMessage());
        }
    }

    private void ensureTableExists() {
        String ddl = "CREATE TABLE IF NOT EXISTS job51_option (" +
                " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                " type VARCHAR(50)," +
                " name VARCHAR(100)," +
                " code VARCHAR(100)," +
                " sort_order INTEGER," +
                " created_at DATETIME," +
                " updated_at DATETIME" +
                ")";
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_job51_option_type ON job51_option(type)");
            log.info("确保 job51_option 表已存在");
        } catch (Exception e) {
            log.warn("创建 job51_option 表失败: {}", e.getMessage());
        }
    }

    private void seedOptions() {
        // 工作年限 workYear（dictStore.d_search_workyear）
        seed("workYear", "不限", "", 0);
        seed("workYear", "在校生/应届生", "01", 1);
        seed("workYear", "1-3年", "02", 2);
        seed("workYear", "3-5年", "03", 3);
        seed("workYear", "5-10年", "04", 4);
        seed("workYear", "10年以上", "05", 5);
        seed("workYear", "无需经验", "06", 6);

        // 学历 degree（d_search_degreefrom）
        seed("degree", "不限", "", 0);
        seed("degree", "初中及以下", "01", 1);
        seed("degree", "高中/中技/中专", "02", 2);
        seed("degree", "大专", "03", 3);
        seed("degree", "本科", "04", 4);
        seed("degree", "硕士", "05", 5);
        seed("degree", "博士", "06", 6);
        seed("degree", "无学历要求", "07", 7);

        // 公司性质 companyType（d_search_cottype）
        seed("companyType", "不限", "", 0);
        seed("companyType", "外资（欧美）", "01", 1);
        seed("companyType", "外资（非欧美）", "02", 2);
        seed("companyType", "合资", "03", 3);
        seed("companyType", "国企", "04", 4);
        seed("companyType", "民营", "05", 5);
        seed("companyType", "外企代表处", "06", 6);
        seed("companyType", "政府机关", "07", 7);
        seed("companyType", "事业单位", "08", 8);
        seed("companyType", "非营利组织", "09", 9);
        seed("companyType", "已上市", "10", 10);
        seed("companyType", "创业公司", "11", 11);

        // 公司规模 companySize（d_search_companysize）
        seed("companySize", "不限", "", 0);
        seed("companySize", "少于50人", "01", 1);
        seed("companySize", "50-150人", "02", 2);
        seed("companySize", "150-500人", "03", 3);
        seed("companySize", "500-1000人", "04", 4);
        seed("companySize", "1000-5000人", "05", 5);
        seed("companySize", "5000-10000人", "06", 6);
        seed("companySize", "10000人以上", "07", 7);

        // 工作类型 jobType（d_search_jobterm）
        seed("jobType", "不限", "", 0);
        seed("jobType", "全职", "01", 1);
        seed("jobType", "兼职", "02", 2);
        seed("jobType", "实习", "03", 3);

        // 补充/校正薪资档（与当前官网「其他筛选」一致；旧档 01-12 保留兼容）
        seed("salary", "8千以下", "201", 13);
    }

    private void seed(String type, String name, String code, int sortOrder) {
        try {
            Job51OptionEntity existing = QueryChain.of(job51OptionMapper)
                    .where(JOB51_OPTION.TYPE.eq(type))
                    .and(JOB51_OPTION.CODE.eq(code))
                    .limit(1)
                    .one();
            if (existing != null) return;

            LocalDateTime now = LocalDateTime.now();
            Job51OptionEntity e = new Job51OptionEntity();
            e.setType(type);
            e.setName(name);
            e.setCode(code);
            e.setSortOrder(sortOrder);
            e.setCreatedAt(now);
            e.setUpdatedAt(now);
            job51OptionMapper.insert(e);
        } catch (Exception ex) {
            log.warn("插入51job选项失败 type={} name={} code={}: {}", type, name, code, ex.getMessage());
        }
    }
}
