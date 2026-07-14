package com.getjobs.application.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Table("liepin_config")
public class LiepinConfigEntity {
    @Id(keyType = KeyType.Auto)
    /** 主键ID */
    private Long id;

    /** 搜索关键词 */
    private String keywords;

    /** 城市（名称或代码） */
    private String city;

    /** 薪资代码或范围（预设档位 code，或自定义如 16$30） */
    private String salaryCode;

    /** 招聘者活跃（pubTime） */
    private String pubTime;

    /** 工作经验（workYearCode） */
    private String workYearCode;

    /** 学历要求（eduLevel） */
    private String eduLevel;

    /** 职位类型（jobKind） */
    private String jobKind;

    /** 企业规模（compScale） */
    private String compScale;

    /** 融资阶段（compStage） */
    private String compStage;

    /** 企业性质（compKind） */
    private String compKind;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
