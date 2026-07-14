package com.getjobs.application.init;

import com.getjobs.application.entity.LiepinOptionEntity;
import com.getjobs.application.mapper.LiepinOptionMapper;
import com.mybatisflex.core.query.QueryChain;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;

import static com.getjobs.application.entity.table.LiepinOptionTableDef.LIEPIN_OPTION;

/**
 * 启动时确保 liepin_option 表存在，并写入猎聘官网筛选字典（若不存在）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiepinOptionInitializer implements CommandLineRunner {

    private final DataSource dataSource;
    private final LiepinOptionMapper liepinOptionMapper;

    @Override
    public void run(String... args) {
        ensureTableExists();
        ensureConfigColumns();
        seedOptions();
    }

    private void ensureConfigColumns() {
        String[] columns = {
                "pub_time VARCHAR(50)",
                "work_year_code VARCHAR(50)",
                "edu_level VARCHAR(50)",
                "job_kind VARCHAR(50)",
                "comp_scale VARCHAR(50)",
                "comp_stage VARCHAR(50)",
                "comp_kind VARCHAR(50)"
        };
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            for (String col : columns) {
                try {
                    stmt.execute("ALTER TABLE liepin_config ADD COLUMN " + col);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.warn("扩展 liepin_config 表失败: {}", e.getMessage());
        }
    }

    private void ensureTableExists() {
        String ddl = "CREATE TABLE IF NOT EXISTS liepin_option (" +
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
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_liepin_option_type ON liepin_option(type)");
            log.info("确保 liepin_option 表已存在");
        } catch (Exception e) {
            log.warn("创建 liepin_option 表失败: {}", e.getMessage());
        }
    }

    private void seedOptions() {
        // 薪资（年薪档位，对应 URL 参数 salaryCode）
        seed("salary", "10万以下", "1", 1);
        seed("salary", "10-15万", "2", 2);
        seed("salary", "16-20万", "3", 3);
        seed("salary", "21-30万", "4", 4);
        seed("salary", "31-50万", "5", 5);
        seed("salary", "51-100万", "6", 6);
        seed("salary", "100万以上", "7", 7);

        // 招聘者活跃（pubTime）
        seed("pubTime", "不限", "", 0);
        seed("pubTime", "一天以内", "1", 1);
        seed("pubTime", "三天以内", "3", 2);
        seed("pubTime", "一周以内", "7", 3);
        seed("pubTime", "一个月以内", "30", 4);

        // 工作经验（workYearCode）
        seed("experience", "不限", "", 0);
        seed("experience", "应届生", "1", 1);
        seed("experience", "实习生", "2", 2);
        seed("experience", "1年以内", "0$1", 3);
        seed("experience", "1-3年", "1$3", 4);
        seed("experience", "3-5年", "3$5", 5);
        seed("experience", "5-10年", "5$10", 6);
        seed("experience", "10年以上", "10$999", 7);

        // 学历（eduLevel）
        seed("degree", "不限", "", 0);
        seed("degree", "初中及以下", "090", 1);
        seed("degree", "高中", "080", 2);
        seed("degree", "中专/中技", "060", 3);
        seed("degree", "大专", "050", 4);
        seed("degree", "本科", "040", 5);
        seed("degree", "硕士", "030", 6);
        seed("degree", "MBA/EMBA", "020", 7);
        seed("degree", "博士", "010", 8);

        // 企业规模（compScale）
        seed("scale", "不限", "", 0);
        seed("scale", "1-49人", "010", 1);
        seed("scale", "50-99人", "020", 2);
        seed("scale", "100-499人", "030", 3);
        seed("scale", "500-999人", "040", 4);
        seed("scale", "1000-2000人", "050", 5);
        seed("scale", "2000-5000人", "060", 6);
        seed("scale", "5000-10000人", "070", 7);
        seed("scale", "10000人以上", "080", 8);

        // 融资阶段（compStage）
        seed("stage", "不限", "", 0);
        seed("stage", "天使轮", "01", 1);
        seed("stage", "A轮", "02", 2);
        seed("stage", "B轮", "03", 3);
        seed("stage", "C轮", "04", 4);
        seed("stage", "D轮及以上", "05", 5);
        seed("stage", "已上市", "06", 6);
        seed("stage", "战略融资", "07", 7);
        seed("stage", "融资未公开", "08", 8);
        seed("stage", "不需要融资", "09", 9);

        // 企业性质（compKind）
        seed("compKind", "不限", "", 0);
        seed("compKind", "外商独资", "010", 1);
        seed("compKind", "中外合资", "020", 2);
        seed("compKind", "民营企业", "030", 3);
        seed("compKind", "国有企业", "040", 4);
        seed("compKind", "国内上市公司", "050", 5);
        seed("compKind", "政府机关", "060", 6);
        seed("compKind", "事业单位", "070", 7);
        seed("compKind", "其他", "080", 8);

        // 职位类型（jobKind）：1=猎头职位，2=企业职位
        seed("jobType", "不限", "", 0);
        seed("jobType", "猎头职位", "1", 1);
        seed("jobType", "企业职位", "2", 2);

        seedCities();
    }

    private void seedCities() {
        // 城市（city/dq），编码与猎聘官网搜索参数一致
        seed("city", "全国", "410", 0);
        seed("city", "北京", "010", 1);
        seed("city", "上海", "020", 2);
        seed("city", "广州", "050020", 3);
        seed("city", "深圳", "050090", 4);
        seed("city", "天津", "030", 5);
        seed("city", "重庆", "040", 6);
        seed("city", "苏州", "060080", 7);
        seed("city", "南京", "060020", 8);
        seed("city", "杭州", "070020", 9);
        seed("city", "武汉", "170020", 10);
        seed("city", "成都", "280020", 11);
        seed("city", "西安", "270020", 12);
        seed("city", "大连", "210040", 13);
        seed("city", "宁波", "070030", 14);
        seed("city", "无锡", "060100", 15);
        seed("city", "长沙", "180020", 16);
        seed("city", "青岛", "250070", 17);
        seed("city", "郑州", "150020", 18);
        seed("city", "合肥", "190020", 19);
        seed("city", "厦门", "080040", 20);
        seed("city", "福州", "080020", 21);
        seed("city", "济南", "250020", 22);
        seed("city", "沈阳", "210020", 23);
        seed("city", "东莞", "050100", 24);
        seed("city", "佛山", "050040", 25);
        seed("city", "常州", "060040", 26);
        seed("city", "南通", "060060", 27);
        seed("city", "徐州", "060070", 28);
        seed("city", "温州", "070040", 29);
        seed("city", "嘉兴", "070050", 30);
        seed("city", "绍兴", "070060", 31);
        seed("city", "金华", "070070", 32);
        seed("city", "惠州", "050110", 33);
        seed("city", "中山", "050120", 34);
        seed("city", "珠海", "050130", 35);
        seed("city", "泉州", "080050", 36);
        seed("city", "烟台", "250080", 37);
        seed("city", "石家庄", "140020", 38);
        seed("city", "哈尔滨", "160020", 39);
        seed("city", "长春", "230020", 40);
        seed("city", "南昌", "200020", 41);
        seed("city", "昆明", "310020", 42);
        seed("city", "贵阳", "120020", 43);
        seed("city", "南宁", "110020", 44);
        seed("city", "海口", "130020", 45);
        seed("city", "太原", "260020", 46);
        seed("city", "呼和浩特", "220020", 47);
        seed("city", "兰州", "320020", 48);
        seed("city", "银川", "330020", 49);
        seed("city", "西宁", "240020", 50);
        seed("city", "拉萨", "290020", 51);
        seed("city", "乌鲁木齐", "300020", 52);
    }

    private void seed(String type, String name, String code, int sortOrder) {
        try {
            LiepinOptionEntity existing = QueryChain.of(liepinOptionMapper)
                    .where(LIEPIN_OPTION.TYPE.eq(type))
                    .and(LIEPIN_OPTION.CODE.eq(code))
                    .limit(1)
                    .one();
            if (existing != null) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            LiepinOptionEntity entity = new LiepinOptionEntity();
            entity.setType(type);
            entity.setName(name);
            entity.setCode(code);
            entity.setSortOrder(sortOrder);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            liepinOptionMapper.insert(entity);
        } catch (Exception e) {
            log.warn("插入猎聘选项失败 type={} code={}: {}", type, code, e.getMessage());
        }
    }
}
