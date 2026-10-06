package com.city.garbage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** 异味传感器读数留痕：构成异味曲线，供街道端观察整改前后异味是否下降 */
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
    private int odorValue;

    /** 是否达到超标阈值 */
    private boolean exceeded;

    @Column(name = "recorded_at", nullable = false)
    private OffsetDateTime recordedAt;
}
