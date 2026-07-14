package com.getjobs.application.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * boss_data 表实体
 */
@Data
@Table("boss_data")
public class BossJobDataEntity {
    @Id(keyType = KeyType.Auto)
    private Long id;

    @Column("encrypt_id")
    private String encryptId;

    @Column("encrypt_user_id")
    private String encryptUserId;

    @Column("company_name")
    private String companyName;

    @Column("job_name")
    private String jobName;

    @Column("salary")
    private String salary;

    @Column("location")
    private String location;

    @Column("experience")
    private String experience;

    @Column("degree")
    private String degree;

    @Column("hr_name")
    private String hrName;

    @Column("hr_position")
    private String hrPosition;

    @Column("hr_active_status")
    private String hrActiveStatus;

    @Column("delivery_status")
    private String deliveryStatus; // 默认 未投递 / 已投递 / 已过滤 / 投递失败

    @Column("job_description")
    private String jobDescription;

    @Column("job_url")
    private String jobUrl;

    @Column("recruitment_status")
    private String recruitmentStatus;

    @Column("company_address")
    private String companyAddress;

    @Column("industry")
    private String industry;

    @Column("introduce")
    private String introduce;

    @Column("financing_stage")
    private String financingStage;

    @Column("company_scale")
    private String companyScale;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;
}
