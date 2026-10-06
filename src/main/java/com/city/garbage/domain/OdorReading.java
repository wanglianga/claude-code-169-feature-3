package com.city.garbage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 异味读数曲线点：设备每次上报异味值都留痕，整改反馈时的设备读数也记入，
 * 街道端据此观察整改前后异味是否下降。
 */
@Getter
@Setter
@Entity
@Table(name = "odor_reading", indexes = {
        @Index(name = "idx_odor_reading_room_time", columnList = "room_code,recorded_at")
})
public class OdorReading {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_code", nullable = false, length = 32)
    private String roomCode;

    /** 异味值 0-100 */
    @Column(name = "odor_value", nullable = false)
    private Integer value;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ReadingSource source = ReadingSource.DEVICE;

    /** 整改反馈读数关联的整改单 */
    @Column(name = "rectification_id")
    private Long rectificationId;

    private OffsetDateTime recordedAt;
}
