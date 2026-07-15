package com.getjobs.application.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Table("zhilian_config")
public class ZhilianConfigEntity {
    @Id(keyType = KeyType.Auto)
    /** 主键ID */
    private Long id;

    /** 搜索关键词（逗号或括号列表，例如 "[Java,后端]" 或 "Java,后端"） */
    private String keywords;

    /** 城市（中文名或代码，单值） */
    private String cityCode;

    /** 薪资范围（中文名或代码，单值；对应 URL 参数 sl） */
    private String salary;

    /** 工作经验（对应 URL 参数 we） */
    private String experience;

    /** 学历要求（对应 URL 参数 el） */
    private String degree;

    /** 职位类型（对应 URL 参数 et） */
    private String jobType;

    /** 公司性质（对应 URL 参数 ct） */
    private String companyType;

    /** 公司规模（对应 URL 参数 cs） */
    private String companySize;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
