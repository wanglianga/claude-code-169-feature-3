package com.city.garbage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 异味反复整改单：垃圾房一周内多次异味超标时生成，
 * 派生消杀、桶体清洗、排风设备检查、清运频次复盘任务；
 * 物业整改后上传照片与设备读数进入观察期，到期评估异味/投诉/满溢，
 * 仍超标则升级下一轮并给出针对性建议（排风/桶体密封/清运频次，而非重复消杀）。
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

    @Column(name = "room_code", nullable = false, length = 32)
    private String roomCode;

    @Column(name = "community_id")
    private Long communityId;

    /** 关联的治理联动单号（整改任务挂在该单上） */
    @Column(name = "case_no", length = 40)
    private String caseNo;

    /** 第几轮整改（同一垃圾房反复超标时递增） */
    @Column(name = "round_no", nullable = false)
    private int roundNo = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RectificationStatus status = RectificationStatus.OPEN;

    /** 触发时一周窗口内的超标次数 */
    @Column(name = "trigger_count", nullable = false)
    private int triggerCount;

    /** 系统整改建议（升级轮次会建议排风/桶体密封/清运频次，而非重复消杀） */
    @Column(length = 512)
    private String suggestion;

    /** 整改责任人（同步街道端） */
    @Column(name = "assignee_name", length = 64)
    private String assigneeName;

    /** 复查时间（同步街道端；到期由定时巡检评估整改效果） */
    @Column(name = "review_at")
    private OffsetDateTime reviewAt;

    /** 整改反馈提交时间 */
    @Column(name = "reported_at")
    private OffsetDateTime reportedAt;

    /** 闭环时的观察结论，如“观察期内超标2次、投诉1起” */
    @Column(name = "outcome_note", length = 255)
    private String outcomeNote;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime closedAt;
}
