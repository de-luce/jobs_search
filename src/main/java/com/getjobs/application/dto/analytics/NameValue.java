package com.getjobs.application.dto.analytics;

/** 通用 name-value 项 */
public class NameValue {
    public String name;
    public long value;

    public NameValue() {}

    public NameValue(String name, long value) {
        this.name = name;
        this.value = value;
    }
}
