package com.city.garbage.service;

import com.city.garbage.config.LinkageProperties;
import com.city.garbage.domain.*;
import com.city.garbage.event.DeviceEvent;
import com.city.garbage.repo.*;
import com.city.garbage.support.BusinessException;
import com.city.garbage.support.TimeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 设备事件处理：刷新垃圾房统一状态，并把满溢/异味/断网/消杀/清运车等
 * 设备信号转成告警交给联动引擎。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceIngestService {

    private final GarbageRoomRepository roomRepository;
    private final TruckVisitRepository visitRepository;
    private final DeviceEventLogRepository logRepository;
    private final LinkageEngine engine;
    private final OdorRectificationService odorRectificationService;
    private final LinkageProperties props;
    private final TimeService timeService;
    private final ObjectMapper objectMapper;

    @Transactional
    public void ingest(DeviceEvent e) {
        if (e == null || e.getEventType() == null || e.getRoomCode() == null) {
            throw new BusinessException("设备事件缺少 eventType/roomCode");
        }
        GarbageRoom room = roomRepository.findByCode(e.getRoomCode())
                .orElseThrow(() -> new BusinessException("垃圾房不存在: " + e.getRoomCode()));
        OffsetDateTime now = timeService.now();

        persistLog(e, now);

        switch (e.getEventType()) {
            case HEARTBEAT -> onHeartbeat(room);
            case KITCHEN_FULL -> onKitchenFull(room, e, now);
            case OTHER_CAPACITY -> onOtherCapacity(room, e, now);
            case ODOR -> onOdor(room, e);
            case ACCESS -> room.setDoorOpen(Boolean.TRUE.equals(e.getOpened()));
            case DISINFECTION -> onDisinfection(room, e, now);
            case TRUCK_ARRIVAL -> onTruck(room, e, now);
            // 摄像头识别与称重由居民投放接口 /api/resident/disposals 汇聚处理
            case CAMERA_DETECTION, WEIGHT -> log.debug("摄像头/称重事件已留痕，投放以投放记录为准 room={}", room.getCode());
        }
        roomRepository.save(room);
    }

    private void onHeartbeat(GarbageRoom room) {
        room.setLastHeartbeatAt(timeService.now());
        if (!room.isOnline()) {
            room.setOnline(true);
            engine.resolveByType(room.getCode(), AlertType.DEVICE_OFFLINE, "设备恢复心跳，联网正常");
        }
    }

    private void onKitchenFull(GarbageRoom room, DeviceEvent e, OffsetDateTime now) {
        Integer pct = e.getCapacityPct();
        if (pct != null) room.setKitchenCapacityPct(pct);
        boolean full = pct != null ? pct >= props.getBinFullThreshold() : true;
        if (full) {
            room.setKitchenFull(true);
            if (room.getKitchenFullSince() == null) room.setKitchenFullSince(now);
            if (!room.isTruckPresent()) {
                engine.raiseAlert(room, AlertType.KITCHEN_FULL_NO_TRUCK, AlertSeverity.URGENT,
                        "厨余桶已满(" + room.getKitchenCapacityPct() + "%)，清运车尚未到场",
                        "KITCHEN_FULL_NO_TRUCK:" + room.getCode(), null, null);
            }
        } else {
            room.setKitchenFull(false);
            room.setKitchenFullSince(null);
            engine.resolveByType(room.getCode(), AlertType.KITCHEN_FULL_NO_TRUCK, "厨余桶容量已降至阈值以下");
        }
    }

    private void onOtherCapacity(GarbageRoom room, DeviceEvent e, OffsetDateTime now) {
        Integer pct = e.getCapacityPct();
        if (pct == null) return;
        room.setOtherCapacityPct(pct);
        if (pct >= props.getBinFullThreshold()) {
            if (room.getOtherFullSince() == null) room.setOtherFullSince(now);
            engine.raiseAlert(room, AlertType.OTHER_BIN_FULL, AlertSeverity.HIGH,
                    "其他垃圾桶容量" + pct + "%，需要安排清运",
                    "OTHER_BIN_FULL:" + room.getCode(), null, null);
        } else {
            room.setOtherFullSince(null);
            engine.resolveByType(room.getCode(), AlertType.OTHER_BIN_FULL, "其他垃圾已清运，容量恢复");
        }
    }

    private void onOdor(GarbageRoom room, DeviceEvent e) {
        Integer odor = e.getOdorValue();
        if (odor == null) return;
        room.setCurrentOdor(odor);
        // 每次上报都记入异味曲线，供街道端观察整改前后异味是否下降
        odorRectificationService.recordReading(room.getCode(), odor, ReadingSource.DEVICE, null);
        if (odor >= props.getOdorThreshold()) {
            engine.raiseAlert(room, AlertType.ODOR, AlertSeverity.HIGH,
                    "异味传感器读数 " + odor + "，超过阈值 " + props.getOdorThreshold(),
                    "ODOR:" + room.getCode(), null, null);
            // 一周内多次超标 -> 生成异味反复整改单（消杀/桶体清洗/排风检查/清运复盘）
            odorRectificationService.onOdorExceedance(room, odor);
        } else {
            // 异味缓解只解除传感器告警；投诉类告警须由物业整改反馈解除
            engine.resolveByType(room.getCode(), AlertType.ODOR, "异味读数回落至 " + odor);
        }
    }

    private void onDisinfection(GarbageRoom room, DeviceEvent e, OffsetDateTime now) {
        if (Boolean.TRUE.equals(e.getDisinfected())) {
            room.setLastDisinfectionAt(now);
            engine.resolveByType(room.getCode(), AlertType.ODOR, "消杀设备已完成消杀，异味解除");
        }
    }

    private void onTruck(GarbageRoom room, DeviceEvent e, OffsetDateTime now) {
        boolean present = Boolean.TRUE.equals(e.getTruckPresent());
        room.setTruckPresent(present);
        if (present) {
            TruckVisit visit = new TruckVisit();
            visit.setRoomCode(room.getCode());
            visit.setStatus(TruckVisitStatus.ARRIVED);
            visit.setKitchenCapacityAtArrival(room.getKitchenCapacityPct());
            visit.setOtherCapacityAtArrival(room.getOtherCapacityPct());
            visit.setArrivedAt(now);
            visitRepository.save(visit);
            // 车已到场，满溢未到场告警解除；容量未清空前 OTHER_BIN_FULL 仍保留
            engine.resolveByType(room.getCode(), AlertType.KITCHEN_FULL_NO_TRUCK, "清运车已到场");
        } else {
            TruckVisit visit = visitRepository
                    .findFirstByRoomCodeAndStatusInOrderByArrivedAtDesc(room.getCode(),
                            List.of(TruckVisitStatus.ARRIVED))
                    .orElseGet(() -> {
                        TruckVisit v = new TruckVisit();
                        v.setRoomCode(room.getCode());
                        v.setArrivedAt(now);
                        return v;
                    });
            visit.setStatus(TruckVisitStatus.DEPARTED);
            visit.setDepartedAt(now);
            visitRepository.save(visit);
            room.setLastCollectionAt(now);
            room.setKitchenCapacityPct(0);
            room.setOtherCapacityPct(0);
            room.setKitchenFull(false);
            room.setOtherFullSince(null);
            room.setKitchenFullSince(null);
            room.setCurrentOdor(Math.min(room.getCurrentOdor(), 30));
            engine.resolveByType(room.getCode(), AlertType.KITCHEN_FULL_NO_TRUCK, "清运完成，桶已清空");
            engine.resolveByType(room.getCode(), AlertType.OTHER_BIN_FULL, "清运完成，桶已清空");
            log.info("清运车离场 room={}，等待清洁确认（{}分钟内）", room.getCode(), props.getNotCleanedMinutes());
        }
    }

    private void persistLog(DeviceEvent e, OffsetDateTime now) {
        DeviceEventLog l = new DeviceEventLog();
        l.setRoomCode(e.roomCode());
        l.setDeviceCode(e.deviceCode());
        l.setEventType(e.eventType().name());
        try {
            l.setPayload(objectMapper.writeValueAsString(e));
        } catch (JsonProcessingException ex) {
            l.setPayload(e.toString());
        }
        l.setOccurredAt(e.occurredAt() != null ? e.occurredAt() : now);
        l.setReceivedAt(now);
        logRepository.save(l);
    }
}
