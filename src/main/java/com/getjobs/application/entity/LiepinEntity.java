package com.getjobs.application.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 猎聘岗位快照数据实体
 * 保存从猎聘接口获取的有价值字段
 */
@Data
@Table("liepin_data")
public class LiepinEntity {
    // ========== 岗位字段 ==========
    @Id
    @Column("job_id")
    private Long jobId;           // job.jobId
    private String jobTitle;      // job.title
    private String jobLink;       // job.link
    private String jobSalaryText; // job.salary
    private String jobArea;       // job.dq
    private String jobEduReq;     // job.requireEduLevel
    private String jobExpReq;     // job.requireWorkYears
    private String jobPublishTime;// job.refreshTime

    // ========== 公司字段 ==========
    private Long compId;          // comp.compId
    private String compName;      // comp.compName
    private String compIndustry;  // comp.compIndustry
    private String compScale;     // comp.compScale

    // ========== HR字段 ==========
    private String hrId;          // recruiter.recruiterId
    private String hrName;        // recruiter.recruiterName
    private String hrTitle;       // recruiter.recruiterTitle
    private String hrImId;        // recruiter.imId

    // ========== 投递状态（与 Boss/智联一致） ==========
    @Column("delivery_status")
    private String deliveryStatus; // 未投递 / 已投递 / 已过滤 / 投递失败

    // ========== 系统字段 ==========
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
