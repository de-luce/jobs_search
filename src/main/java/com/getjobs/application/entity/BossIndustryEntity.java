package com.getjobs.application.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Table("boss_industry")
public class BossIndustryEntity {
    @Id(keyType = KeyType.Auto)
    private Long id;

    private String name;
    private Integer code;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
