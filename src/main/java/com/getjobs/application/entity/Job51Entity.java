package com.getjobs.application.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.Data;

/**
 * 51job 岗位快照数据实体
 * 保存从 51job 搜索接口返回的有价值字段
 */
@Data
@Table("job51_data")
public class Job51Entity {
    // 岗位字段
    @Id
    @Column("job_id")
    private Long jobId;
    private String jobTitle;
    private String jobLink;
    private String jobSalaryText;
    private String jobArea;
    private String jobEduReq;
    private String jobExpReq;
    private String jobPublishTime;

    // 公司/HR字段
    private Long compId;
    private String compName;
    private String compIndustry;
    private String compScale;
    private String hrId;
    private String hrName;
    private String hrTitle;

    // 投递状态（与 Boss/智联一致）
    @Column("delivery_status")
    private String deliveryStatus; // 未投递 / 已投递 / 已过滤 / 投递失败

    private String createTime;
    private String updateTime;
}
