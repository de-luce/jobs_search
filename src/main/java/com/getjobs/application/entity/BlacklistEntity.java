package com.getjobs.application.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 全局黑名单（公司名称等），存于 boss_blacklist 表。
 * type=company 时 value 为公司名子串，投递前对岗位公司名做 LIKE（contains）匹配。
 */
@Data
@Table("boss_blacklist")
public class BlacklistEntity {

    public static final String TYPE_COMPANY = "company";

    @Id(keyType = KeyType.Auto)
    private Long id;

    /** 黑名单类型，如 company */
    @Column("type")
    private String type;

    /** 匹配值（公司名子串） */
    @Column("value")
    private String value;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;
}
