package com.city.garbage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 异味反复整改单：某垃圾房一周内多次异味超标时开具，
 * 派生消杀、桶体清洗、排风设备检查、清运频次复盘任务；
 * 物业完成整改后上传照片与设备读数进入观察期，街道端同步责任人与复查时间；
 * 观察期内再超标则升级深度排查（排风/桶体密封/清运频次），而非简单重复消杀。
 */
@Getter
@Setter
@Entity
@Table(name = "odor_rectification", indexes = {
        @Index(name = "idx_rect_room", columnList = "room_code"),
        @Index(name = "idx_rect_status", columnList = "status")
})
public class OdorRectification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rect_no", nullable = false, unique = true, length = 40)
    private String rectNo;

    @Column(name = "room_code", nullable = false, length = 32)
    private String roomCode;

    @Column(name = "community_id")
    private Long communityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OdorRectificationStatus status = OdorRectificationStatus.RECTIFYING;

    /** 触发时统计窗口（天），默认 7 天 */
    private int windowDays = 7;

    /** 触发时窗口内异味超标次数 */
    private int triggerCount = 0;

    /** 整改轮次：首次开单为 1，每次升级后再整改 +1 */
    private int round = 1;

    /** 整改责任人（物业完成整改时登记，同步街道端） */
    @Column(name = "owner_name", length = 64)
    private String ownerName;

    /** 复查时间（同步街道端；观察期结束时间） */
    @Column(name = "review_at")
    private OffsetDateTime reviewAt;

    /** 整改佐证照片，逗号分隔的 URL/文件标识 */
    @Column(length = 1024)
    private String photos;

    /** 整改后设备读数（异味值） */
    @Column(name = "device_reading")
    private Integer deviceReading;

    /** 整改结果说明 */
    @Column(name = "rectify_note", length = 512)
    private String rectifyNote;

    /** 系统建议（升级时给出：检查排风/桶体密封/清运频次，而非重复消杀） */
    @Column(length = 512)
    private String suggestion;

    private OffsetDateTime observationStartAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime closedAt;
}
