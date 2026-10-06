package com.city.garbage.service;

import com.city.garbage.config.LinkageProperties;
import com.city.garbage.domain.*;
import com.city.garbage.repo.*;
import com.city.garbage.support.TimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 定时巡检：
 * 1) 心跳超时 -> 设备断网告警 -> 物业维修任务，恢复心跳自动销警；
 * 2) 清运离场超时未清洁 -> “清运后未清洁” -> 物业补保洁；
 * 3) 投放积分长期未到账 -> “积分未到账” -> 物业核查补发；
 * 4) 异味整改复查到期 -> 评估观察期内异味曲线/投诉/满溢，有效则闭环，仍超标则升级下一轮整改。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LinkageWatchdog {

    private final GarbageRoomRepository roomRepository;
    private final AlertRepository alertRepository;
    private final DisposalRepository disposalRepository;
    private final LinkageEngine engine;
    private final SanitationService sanitationService;
    private final DisposalService disposalService;
    private final OdorRectificationService odorRectificationService;
    private final LinkageProperties props;
    private final TimeService timeService;

    @Scheduled(fixedDelayString = "${app.watchdog.fixed-delay-ms:30000}")
    @Transactional
    public void scan() {
        checkOffline();
        sanitationService.checkNotCleaned(props.getNotCleanedMinutes());
        checkPointsUnsettled();
        odorRectificationService.evaluateDue();
    }

    private void checkOffline() {
        OffsetDateTime threshold = timeService.now().minusMinutes(props.getOfflineMinutes());
        for (GarbageRoom room : roomRepository.findAll()) {
            OffsetDateTime hb = room.getLastHeartbeatAt();
            boolean timeout = hb == null || hb.isBefore(threshold);
            if (timeout && room.isOnline()) {
                room.setOnline(false);
                roomRepository.save(room);
                engine.raiseAlert(room, AlertType.DEVICE_OFFLINE, AlertSeverity.HIGH,
                        "设备心跳中断超过" + props.getOfflineMinutes() + "分钟，判定断网",
                        "OFFLINE:" + room.getCode(), null, null);
            }
        }
    }

    private void checkPointsUnsettled() {
        OffsetDateTime deadline = timeService.now().minusSeconds(props.getUnsettledSeconds());
        for (DisposalRecord r : disposalRepository.findAll()) {
            if (r.getStatus() == DisposalStatus.PENDING_POINTS
                    && r.getCreatedAt() != null && r.getCreatedAt().isBefore(deadline)) {
                String dedupe = "POINTS_UNSETTLED:DISPOSAL:" + r.getId();
                boolean exists = alertRepository.existsByDedupeKeyAndStatus(dedupe, AlertStatus.OPEN)
                        || alertRepository.existsByDedupeKeyAndStatus(dedupe, AlertStatus.LINKED);
                if (!exists) {
                    roomRepository.findByCode(r.getRoomCode()).ifPresent(room ->
                            engine.raiseAlert(room, AlertType.POINTS_UNSETTLED, AlertSeverity.MEDIUM,
                                    "投放#" + r.getId() + "积分超过" + props.getUnsettledSeconds()
                                            + "秒未到账，需核查补发", dedupe, r.getId(), null));
                }
            }
        }
    }

    /** 可由管理接口触发：立即核查补发所有未到账积分 */
    public int runSettleNow() {
        return disposalService.settlePendingPoints();
    }
}
