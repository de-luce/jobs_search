package com.getjobs.application.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * zhilian_data 表实体
 */
@Data
@Table("zhilian_data")
public class ZhilianJobDataEntity {
    @Id(keyType = KeyType.Auto)
    private Long id;

    @Column("job_id")
    private String jobId;

    @Column("job_title")
    private String jobTitle;

    @Column("job_link")
    private String jobLink;

    @Column("salary")
    private String salary;

    @Column("location")
    private String location;

    @Column("experience")
    private String experience;

    @Column("degree")
    private String degree;

    @Column("company_name")
    private String companyName;

    @Column("delivery_status")
    private String deliveryStatus; // 未投递 / 已投递 / 已过滤 / 投递失败

    @Column("create_time")
    private LocalDateTime createTime;

    @Column("update_time")
    private LocalDateTime updateTime;
}
