package com.getjobs.application.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Flex Mapper 扫描：com.getjobs.application.mapper
 * （Flex 沿用 MyBatis Spring 的 @MapperScan，无独立 Flex MapperScan 包）
 */
@Configuration
@MapperScan("com.getjobs.application.mapper")
public class DataMapperConfig {
}
