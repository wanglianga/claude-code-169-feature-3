package com.city.garbage.domain;

/** 异味读数来源 */
public enum ReadingSource {
    DEVICE,               // 异味传感器设备上报
    RECTIFICATION_REPORT  // 物业整改反馈时填报的设备读数
}
