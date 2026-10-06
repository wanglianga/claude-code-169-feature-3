package com.city.garbage.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 物业整改反馈：上传整改照片与设备读数，并登记整改责任人与复查时间（同步街道端）。
 *
 * @param assigneeName   整改责任人（必填）
 * @param odorReading    整改后异味设备读数（必填，记入异味曲线）
 * @param exhaustReading 排风设备读数（如风量/运行电流，可选）
 * @param photoUrls      整改现场照片 URL 列表
 * @param note           整改说明
 * @param reviewAt       复查时间（缺省为提交后 48 小时，可在 app.linkage.odor-review-hours 配置）
 */
public record RectificationReportRequest(
        @NotBlank(message = "整改责任人必填") String assigneeName,
        @NotNull(message = "整改后异味设备读数必填") Integer odorReading,
        Integer exhaustReading,
        List<String> photoUrls,
        String note,
        OffsetDateTime reviewAt
) {}
