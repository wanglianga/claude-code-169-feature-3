package com.city.garbage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 整改回执：物业完成整改后上传的照片与设备读数（异味读数、排风设备读数），
 * 街道端可查看整改证据并对比整改前后异味曲线。
 */
@Getter
@Setter
@Entity
@Table(name = "rectification_report", indexes = {
        @Index(name = "idx_rect_report_rect", columnList = "rectification_id")
})
public class RectificationReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rectification_id", nullable = false)
    private Long rectificationId;

    /** 整改现场照片 URL，逗号分隔 */
    @Column(name = "photo_urls", length = 1024)
    private String photoUrls;

    /** 整改后异味设备读数 */
    @Column(name = "odor_reading")
    private Integer odorReading;

    /** 排风设备读数（如风量/运行电流） */
    @Column(name = "exhaust_reading")
    private Integer exhaustReading;

    @Column(length = 512)
    private String note;

    /** 填报人（整改责任人） */
    @Column(name = "created_by", length = 64)
    private String createdBy;

    private OffsetDateTime createdAt;
}
