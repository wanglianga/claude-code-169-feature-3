package com.city.garbage.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;

/** 物业完成异味整改：上传照片、设备读数并登记责任人；可约定复查时间，默认完成时间+观察期 */
public record CompleteRectificationRequest(
        @NotBlank String ownerName,
        @NotEmpty List<String> photos,
        @NotNull Integer deviceReading,
        OffsetDateTime reviewAt,
        String note) {}
