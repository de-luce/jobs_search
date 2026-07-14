package com.getjobs.worker.liepin;

import lombok.Data;

import java.util.List;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 */
@Data
public class LiepinConfig {
    /**
     * 搜索关键词列表
     */
    private List<String> keywords;

    /**
     * 城市编码
     */
    private String cityCode;

    /**
     * 预设薪资档位或自定义年薪范围（均为 salaryCode，如 4 或 18$30）
     */
    private String salaryCode;

    /**
     * @deprecated 猎聘自定义薪资也使用 salaryCode，不再使用独立 salary 参数
     */
    @Deprecated
    private String customSalary;

    /**
     * 招聘者活跃（pubTime）
     */
    private String pubTime;

    /**
     * 工作经验（workYearCode）
     */
    private String workYearCode;

    /**
     * 学历（eduLevel）
     */
    private String eduLevel;

    /**
     * 职位类型（jobKind）
     */
    private String jobKind;

    /**
     * 企业规模（compScale）
     */
    private String compScale;

    /**
     * 融资阶段（compStage）
     */
    private String compStage;

    /**
     * 企业性质（compKind）
     */
    private String compKind;

}
