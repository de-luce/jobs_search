package com.getjobs.application.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 配置实体类
 */
@Data
@Table("config")
public class ConfigEntity {

    /**
     * 主键ID
     */
    @Id(keyType = KeyType.Auto)
    private Long id;

    /**
     * 配置键
     */
    @Column("config_key")
    private String configKey;

    /**
     * 配置值
     */
    @Column("config_value")
    private String configValue;

    /**
     * 配置类型
     */
    @Column("config_type")
    private String configType;

    /**
     * 分类
     */
    @Column("category")
    private String category;

    /**
     * 描述
     */
    @Column("description")
    private String description;

    /**
     * 创建时间
     */
    @Column("created_at")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @Column("updated_at")
    private LocalDateTime updatedAt;
}
