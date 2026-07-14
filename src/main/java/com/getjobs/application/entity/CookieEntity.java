package com.getjobs.application.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Cookie实体类
 */
@Data
@Table("cookie")
public class CookieEntity {

    /**
     * 主键ID
     */
    @Id(keyType = KeyType.Auto)
    private Long id;

    /**
     * 平台名称（boss/zhilian/job51/liepin）
     */
    @Column("platform")
    private String platform;

    /**
     * Cookie值
     */
    @Column("cookie_value")
    private String cookieValue;

    /**
     * 备注
     */
    @Column("remark")
    private String remark;

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
