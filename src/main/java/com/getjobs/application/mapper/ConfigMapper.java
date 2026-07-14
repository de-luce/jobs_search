package com.getjobs.application.mapper;

import com.mybatisflex.core.BaseMapper;
import com.getjobs.application.entity.ConfigEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 配置Mapper接口
 */
@Mapper
public interface ConfigMapper extends BaseMapper<ConfigEntity> {
}
