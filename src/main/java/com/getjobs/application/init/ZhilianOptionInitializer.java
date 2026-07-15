package com.getjobs.application.init;

import com.getjobs.application.entity.ZhilianOptionEntity;
import com.getjobs.application.mapper.ZhilianOptionMapper;
import com.mybatisflex.core.query.QueryChain;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;

import static com.getjobs.application.entity.table.ZhilianOptionTableDef.ZHILIAN_OPTION;

/**
 * 启动时确保 zhilian_option / zhilian_config 列完整，并写入官网搜索筛选项字典。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZhilianOptionInitializer implements CommandLineRunner {

    private final DataSource dataSource;
    private final ZhilianOptionMapper zhilianOptionMapper;

    @Override
    public void run(String... args) {
        ensureTableExists();
        ensureConfigColumns();
        seedOptions();
    }

    private void ensureConfigColumns() {
        String[] columns = {
                "experience VARCHAR(50)",
                "degree VARCHAR(50)",
                "job_type VARCHAR(50)",
                "company_type VARCHAR(100)",
                "company_size VARCHAR(50)"
        };
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            for (String col : columns) {
                try {
                    stmt.execute("ALTER TABLE zhilian_config ADD COLUMN " + col);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.warn("扩展 zhilian_config 表失败: {}", e.getMessage());
        }
    }

    private void ensureTableExists() {
        String ddl = "CREATE TABLE IF NOT EXISTS zhilian_option (" +
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
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_zhilian_option_type ON zhilian_option(type)");
            log.info("确保 zhilian_option 表已存在");
        } catch (Exception e) {
            log.warn("创建 zhilian_option 表失败: {}", e.getMessage());
        }
    }

    private void seedOptions() {
        // 薪资 sl（月薪区间）
        seed("salary", "不限", "0", 0);
        seed("salary", "4K以下", "0000,4000", 1);
        seed("salary", "4K-6K", "4001,6000", 2);
        seed("salary", "6K-8K", "6001,8000", 3);
        seed("salary", "8K-10K", "8001,10000", 4);
        seed("salary", "10K-15K", "10001,15000", 5);
        seed("salary", "15K-25K", "15001,25000", 6);
        seed("salary", "25K-35K", "25001,35000", 7);
        seed("salary", "35K-50K", "35001,50000", 8);
        seed("salary", "50K以上", "50001,9999999", 9);

        // 工作经验 we
        seed("experience", "不限", "", 0);
        seed("experience", "经验不限", "-1", 1);
        seed("experience", "1年以下", "0001", 2);
        seed("experience", "1-3年", "0103", 3);
        seed("experience", "3-5年", "0305", 4);
        seed("experience", "5-10年", "0510", 5);
        seed("experience", "10年以上", "1099", 6);

        // 学历 el
        seed("degree", "不限", "", 0);
        seed("degree", "初中及以下", "9", 1);
        seed("degree", "高中", "7", 2);
        seed("degree", "中专/中技", "12", 3);
        seed("degree", "大专", "5", 4);
        seed("degree", "本科", "4", 5);
        seed("degree", "硕士", "3", 6);
        seed("degree", "MBA/EMBA", "10", 7);
        seed("degree", "博士", "1", 8);

        // 职位类型 et
        seed("jobType", "不限", "", 0);
        seed("jobType", "全职", "2", 1);
        seed("jobType", "兼职/临时", "1", 2);
        seed("jobType", "实习", "4", 3);
        seed("jobType", "校园", "5", 4);

        // 公司性质 ct
        seed("companyType", "不限", "", 0);
        seed("companyType", "国企", "1", 1);
        seed("companyType", "外企", "2;3", 2);
        seed("companyType", "合资", "4", 3);
        seed("companyType", "民营", "5", 4);
        seed("companyType", "上市公司", "9", 5);
        seed("companyType", "股份制企业", "8", 6);
        seed("companyType", "事业单位", "6;10", 7);
        seed("companyType", "其他", "11;12;13;14;15;16;7", 8);

        // 公司规模 cs
        seed("companySize", "不限", "", 0);
        seed("companySize", "20人以下", "1", 1);
        seed("companySize", "20-99人", "2", 2);
        seed("companySize", "100-299人", "3", 3);
        seed("companySize", "300-499人", "8", 4);
        seed("companySize", "500-999人", "4", 5);
        seed("companySize", "1000-9999人", "5", 6);
        seed("companySize", "10000人以上", "6", 7);
    }

    private void seed(String type, String name, String code, int sortOrder) {
        try {
            ZhilianOptionEntity existing = QueryChain.of(zhilianOptionMapper)
                    .where(ZHILIAN_OPTION.TYPE.eq(type))
                    .and(ZHILIAN_OPTION.CODE.eq(code))
                    .limit(1)
                    .one();
            if (existing != null) return;

            LocalDateTime now = LocalDateTime.now();
            ZhilianOptionEntity e = new ZhilianOptionEntity();
            e.setType(type);
            e.setName(name);
            e.setCode(code);
            e.setSortOrder(sortOrder);
            e.setCreatedAt(now);
            e.setUpdatedAt(now);
            zhilianOptionMapper.insert(e);
        } catch (Exception ex) {
            log.warn("插入智联选项失败 type={} name={} code={}: {}", type, name, code, ex.getMessage());
        }
    }
}
