package com.getjobs.application.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Table("job51_config")
public class Job51ConfigEntity {
    @Id(keyType = KeyType.Auto)
    /** 主键ID */
    private Long id;

    /** 搜索关键词（逗号或括号列表，例如 "[Java,后端]" 或 "Java,后端"） */
    private String keywords;

    /** 城市区域（中文名或代码，列表字符串） */
    private String jobArea;

    /** 薪资范围（中文名或代码，列表字符串） */
    private String salary;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
