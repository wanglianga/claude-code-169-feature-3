package com.city.garbage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** 现场治理任务：物业端看到保洁/消杀/维修/复核待办，环卫端看到清运任务及优先级 */
@Getter
@Setter
@Entity
@Table(name = "work_task", indexes = {
        @Index(name = "idx_task_case", columnList = "case_no"),
        @Index(name = "idx_task_status", columnList = "status"),
        @Index(name = "idx_task_type", columnList = "type")
})
public class WorkTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_no", nullable = false, length = 40)
    private String caseNo;

    @Column(name = "room_code", nullable = false, length = 32)
    private String roomCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private TaskType type;

    /** PROPERTY=物业 / SANITATION=环卫 */
    @Column(name = "assignee_role", nullable = false, length = 16)
    private String assigneeRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskStatus status = TaskStatus.PENDING;

    /** 数值越大越优先 */
    private int priority;

    @Column(length = 255)
    private String detail;

    /** 同单同类型未完成任务去重键 */
    @Column(name = "dedupe_key", length = 120)
    private String dedupeKey;

    @Column(length = 64)
    private String assigneeName;

    /** 关联告警 id，告警解除时可自动完成任务 */
    private Long alertId;

    /** 关联异味反复整改单号（整改类任务挂单到整改单，普通联动任务为空） */
    @Column(name = "rect_no", length = 40)
    private String rectNo;

    private OffsetDateTime createdAt;
    private OffsetDateTime assignedAt;
    private OffsetDateTime doneAt;

    @Column(name = "result_note", length = 255)
    private String resultNote;
}
