package com.city.garbage.service;

import com.city.garbage.config.LinkageProperties;
import com.city.garbage.domain.*;
import com.city.garbage.kafka.GovernanceEventPublisher;
import com.city.garbage.repo.*;
import com.city.garbage.support.BusinessException;
import com.city.garbage.support.TimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 异味反复整改：某垃圾房一周内多次异味超标时开具整改单，
 * 派生消杀、桶体清洗、排风设备检查、清运频次复盘四类任务；
 * 物业完成整改后上传照片与设备读数，进入观察期（责任人与复查时间同步街道端）；
 * 观察期内持续观察异味曲线、投诉与满溢次数，仍超标则升级深度排查——
 * 建议检查排风、桶体密封或清运频次，而不是简单重复消杀。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OdorRectificationService {

    private final OdorRectificationRepository rectRepository;
    private final OdorReadingRepository readingRepository;
    private final WorkTaskRepository taskRepository;
    private final AlertRepository alertRepository;
    private final ComplaintRepository complaintRepository;
    private final GarbageRoomRepository roomRepository;
    private final LinkageProperties props;
    private final TimeService timeService;
    private final GovernanceEventPublisher eventPublisher;

    private static final List<OdorRectificationStatus> ACTIVE = List.of(
            OdorRectificationStatus.RECTIFYING,
            OdorRectificationStatus.OBSERVING,
            OdorRectificationStatus.ESCALATED);

    private static final List<AlertType> OVERFLOW_TYPES = List.of(
            AlertType.KITCHEN_FULL_NO_TRUCK, AlertType.OTHER_BIN_FULL);

    /** 每次异味上报都留痕，构成街道端可观察的异味曲线 */
    @Transactional
    public void recordReading(GarbageRoom room, int odorValue) {
        OdorReading r = new OdorReading();
        r.setRoomCode(room.getCode());
        r.setOdorValue(odorValue);
        r.setExceeded(odorValue >= props.getOdorThreshold());
        r.setRecordedAt(timeService.now());
        readingRepository.save(r);
        if (r.isExceeded()) {
            onOdorExceeded(room);
        }
    }

    /**
     * 异味超标后的整改判定：
     * 观察期内再次超标 -> 立即升级深度排查；
     * 无进行中的整改单且窗口内超标次数达到阈值 -> 开具整改单。
     */
    @Transactional
    public void onOdorExceeded(GarbageRoom room) {
        OffsetDateTime now = timeService.now();
        Optional<OdorRectification> active = activeRectification(room.getCode());
        if (active.isPresent()) {
            OdorRectification rect = active.get();
            if (rect.getStatus() == OdorRectificationStatus.OBSERVING) {
                escalate(rect, "观察期内异味再次超标（读数 " + room.getCurrentOdor() + "）");
            }
            return;
        }
        OffsetDateTime from = now.minusDays(props.getOdorRepeatWindowDays());
        long exceeded = readingRepository.countByRoomCodeAndExceededAndRecordedAtBetween(
                room.getCode(), true, from, now);
        if (exceeded >= props.getOdorRepeatThreshold()) {
            createRectification(room, (int) exceeded, now);
        }
    }

    /** 开具整改单：消杀 + 桶体清洗 + 排风设备检查 + 清运频次复盘 */
    private void createRectification(GarbageRoom room, int triggerCount, OffsetDateTime now) {
        OdorRectification rect = new OdorRectification();
        rect.setRectNo("ODR-" + room.getCode() + "-" + System.currentTimeMillis());
        rect.setRoomCode(room.getCode());
        rect.setCommunityId(room.getCommunityId());
        rect.setStatus(OdorRectificationStatus.RECTIFYING);
        rect.setWindowDays(props.getOdorRepeatWindowDays());
        rect.setTriggerCount(triggerCount);
        rect.setCreatedAt(now);
        rect.setUpdatedAt(now);
        rect = rectRepository.save(rect);

        createTask(rect, TaskType.DISINFECTION, 70, "异味反复超标，立即消杀");
        createTask(rect, TaskType.BIN_WASH, 65, "桶体深度清洗，去除残留渗滤液");
        createTask(rect, TaskType.VENTILATION_CHECK, 60, "检查排风设备运行状态与风量");
        createTask(rect, TaskType.COLLECTION_REVIEW, 55,
                "复盘清运频次是否匹配投放量，必要时上调清运班次");

        log.info("异味反复整改开单 room={} rect={} 窗口{}天超标{}次",
                room.getCode(), rect.getRectNo(), rect.getWindowDays(), triggerCount);
        publish(rect, "RECTIFICATION_CREATED",
                "一周内异味超标" + triggerCount + "次，开具整改单（消杀/桶体清洗/排风检查/清运复盘）");
    }

    /**
     * 物业完成整改：上传照片与设备读数，登记责任人，进入观察期。
     * 整改后读数仍超标的，不进入观察期，直接升级深度排查。
     */
    @Transactional
    public OdorRectification complete(String rectNo, String ownerName, List<String> photos,
                                      Integer deviceReading, OffsetDateTime reviewAt, String note) {
        OdorRectification rect = rectRepository.findByRectNo(rectNo)
                .orElseThrow(() -> new BusinessException("整改单不存在: " + rectNo));
        if (rect.getStatus() != OdorRectificationStatus.RECTIFYING
                && rect.getStatus() != OdorRectificationStatus.ESCALATED) {
            throw new BusinessException("整改单当前状态不允许完成整改: " + rect.getStatus());
        }
        List<WorkTask> tasks = taskRepository.findByRectNo(rectNo);
        long undone = tasks.stream().filter(t -> t.getStatus() != TaskStatus.DONE).count();
        if (undone > 0) {
            throw new BusinessException("整改任务尚未全部完成，还剩 " + undone + " 项，不能提交整改结果");
        }
        if (ownerName == null || ownerName.isBlank()) {
            throw new BusinessException("必须登记整改责任人");
        }
        if (photos == null || photos.isEmpty()) {
            throw new BusinessException("必须上传整改照片");
        }
        if (deviceReading == null) {
            throw new BusinessException("必须上传整改后设备读数");
        }

        OffsetDateTime now = timeService.now();
        rect.setOwnerName(ownerName);
        rect.setPhotos(String.join(",", photos));
        rect.setDeviceReading(deviceReading);
        rect.setRectifyNote(note);
        rect.setReviewAt(reviewAt != null ? reviewAt : now.plusDays(props.getOdorObserveDays()));
        rect.setObservationStartAt(now);
        rect.setUpdatedAt(now);

        // 上传的设备读数并入异味曲线，供街道端观察整改后走势
        OdorReading r = new OdorReading();
        r.setRoomCode(rect.getRoomCode());
        r.setOdorValue(deviceReading);
        r.setExceeded(deviceReading >= props.getOdorThreshold());
        r.setRecordedAt(now);
        readingRepository.save(r);

        if (r.isExceeded()) {
            rectRepository.save(rect);
            escalate(rect, "整改后设备读数 " + deviceReading + " 仍超标");
            return rect;
        }

        rect.setStatus(OdorRectificationStatus.OBSERVING);
        rectRepository.save(rect);
        log.info("整改完成进入观察期 rect={} owner={} reviewAt={}", rectNo, ownerName, rect.getReviewAt());
        publish(rect, "RECTIFICATION_OBSERVING",
                "整改完成，责任人 " + ownerName + "，进入" + props.getOdorObserveDays() + "天观察期");
        return rect;
    }

    /**
     * 升级深度排查：清洗消杀后仍超标，问题可能在排风、桶体密封或清运频次，
     * 不再简单重复消杀，改派排风复查、桶体密封检查、清运频次再复盘任务。
     */
    private void escalate(OdorRectification rect, String reason) {
        rect.setStatus(OdorRectificationStatus.ESCALATED);
        rect.setRound(rect.getRound() + 1);
        rect.setUpdatedAt(timeService.now());
        String stats = observationStatsText(rect);
        rect.setSuggestion("清洗消杀后异味仍超标（" + reason + "；" + stats + "），"
                + "建议检查排风设备运行、桶体密封性或上调清运频次，而非简单重复消杀");
        rectRepository.save(rect);

        createTask(rect, TaskType.VENTILATION_CHECK, 75, "异味反复，复查排风设备风量与运行时长");
        createTask(rect, TaskType.BIN_SEAL, 70, "检查桶体密封条与桶盖闭合，必要时更换密封件");
        createTask(rect, TaskType.COLLECTION_REVIEW, 65, "再次复盘清运频次，评估是否上调清运班次");

        log.info("整改升级深度排查 rect={} round={} reason={}", rect.getRectNo(), rect.getRound(), reason);
        publish(rect, "RECTIFICATION_ESCALATED", rect.getSuggestion());
    }

    /** 观察期到期评估：窗口内无超标则闭环，仍超标则升级（兜底，通常超标时已即时升级） */
    @Transactional
    public int evaluateObservations() {
        OffsetDateTime now = timeService.now();
        int evaluated = 0;
        for (OdorRectification rect : rectRepository.findByStatus(OdorRectificationStatus.OBSERVING)) {
            if (rect.getReviewAt() == null || rect.getReviewAt().isAfter(now)) {
                continue;
            }
            long exceeded = readingRepository.countByRoomCodeAndExceededAndRecordedAtBetween(
                    rect.getRoomCode(), true, rect.getObservationStartAt(), now);
            if (exceeded == 0) {
                rect.setStatus(OdorRectificationStatus.CLOSED_EFFECTIVE);
                rect.setClosedAt(now);
                rect.setUpdatedAt(now);
                rectRepository.save(rect);
                log.info("观察期异味未再超标，整改闭环 rect={}", rect.getRectNo());
                publish(rect, "RECTIFICATION_CLOSED",
                        "观察期" + props.getOdorObserveDays() + "天内异味未再超标，整改有效闭环");
            } else {
                escalate(rect, "观察期到期仍有" + exceeded + "次超标");
            }
            evaluated++;
        }
        return evaluated;
    }

    /** 街道端：整改单观察指标（观察期异味超标次数、投诉数、满溢次数） */
    public Map<String, Object> observationStats(OdorRectification rect) {
        OffsetDateTime now = timeService.now();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rectNo", rect.getRectNo());
        m.put("roomCode", rect.getRoomCode());
        m.put("status", rect.getStatus());
        m.put("round", rect.getRound());
        m.put("ownerName", rect.getOwnerName());
        m.put("reviewAt", rect.getReviewAt());
        m.put("triggerCount", rect.getTriggerCount());
        m.put("windowDays", rect.getWindowDays());
        if (rect.getObservationStartAt() != null) {
            OffsetDateTime from = rect.getObservationStartAt();
            m.put("observationExceededCount", readingRepository
                    .countByRoomCodeAndExceededAndRecordedAtBetween(rect.getRoomCode(), true, from, now));
            m.put("observationComplaintCount", complaintRepository
                    .countByRoomCodeAndCreatedAtBetween(rect.getRoomCode(), from, now));
            m.put("observationOverflowCount", alertRepository
                    .countByRoomCodeAndTypeInAndCreatedAtBetween(rect.getRoomCode(), OVERFLOW_TYPES, from, now));
        }
        // 整改前后异味均值对比，街道端据此判断异味是否下降
        m.put("avgOdorBeforeRectification", avgOdor(rect.getRoomCode(),
                rect.getCreatedAt().minusDays(rect.getWindowDays()), rect.getCreatedAt()));
        OffsetDateTime afterFrom = rect.getObservationStartAt() != null
                ? rect.getObservationStartAt() : rect.getCreatedAt();
        m.put("avgOdorAfterRectification", avgOdor(rect.getRoomCode(), afterFrom, now));
        return m;
    }

    /** 街道端：异味曲线（读数时间序列 + 阈值），可观察整改后异味是否下降 */
    public Map<String, Object> odorCurve(String roomCode, int days) {
        GarbageRoom room = roomRepository.findByCode(roomCode)
                .orElseThrow(() -> new BusinessException("垃圾房不存在: " + roomCode));
        OffsetDateTime now = timeService.now();
        List<OdorReading> readings = readingRepository
                .findByRoomCodeAndRecordedAtBetweenOrderByRecordedAtAsc(
                        roomCode, now.minusDays(days), now);
        List<Map<String, Object>> points = new ArrayList<>();
        for (OdorReading r : readings) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("recordedAt", r.getRecordedAt());
            p.put("value", r.getOdorValue());
            p.put("exceeded", r.isExceeded());
            points.add(p);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roomCode", room.getCode());
        m.put("roomName", room.getName());
        m.put("odorThreshold", props.getOdorThreshold());
        m.put("windowDays", days);
        m.put("currentOdor", room.getCurrentOdor());
        m.put("readings", points);
        m.put("rectifications", rectRepository.findByRoomCodeOrderByCreatedAtDesc(roomCode));
        return m;
    }

    public List<OdorRectification> list(String roomCode) {
        if (roomCode == null || roomCode.isBlank()) {
            return rectRepository.findAllByOrderByCreatedAtDesc();
        }
        return rectRepository.findByRoomCodeOrderByCreatedAtDesc(roomCode);
    }

    public OdorRectification get(String rectNo) {
        return rectRepository.findByRectNo(rectNo)
                .orElseThrow(() -> new BusinessException("整改单不存在: " + rectNo));
    }

    public List<WorkTask> tasksOf(String rectNo) {
        return taskRepository.findByRectNo(rectNo);
    }

    private Optional<OdorRectification> activeRectification(String roomCode) {
        List<OdorRectification> list = rectRepository.findByRoomCodeAndStatusIn(roomCode, ACTIVE);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    private void createTask(OdorRectification rect, TaskType type, int priority, String detail) {
        String key = rect.getRectNo() + ":" + type + ":" + rect.getRound();
        var found = taskRepository.findByDedupeKeyAndStatusNot(key, TaskStatus.DONE);
        if (found.isPresent()) {
            WorkTask t = found.get();
            if (t.getPriority() < priority) t.setPriority(priority);
            taskRepository.save(t);
            return;
        }
        WorkTask t = new WorkTask();
        t.setCaseNo(rect.getRectNo());
        t.setRoomCode(rect.getRoomCode());
        t.setType(type);
        t.setAssigneeRole("PROPERTY");
        t.setPriority(priority);
        t.setDetail(detail);
        t.setDedupeKey(key);
        t.setRectNo(rect.getRectNo());
        t.setCreatedAt(timeService.now());
        taskRepository.save(t);
    }

    private String observationStatsText(OdorRectification rect) {
        if (rect.getObservationStartAt() == null) {
            return "整改后读数仍超标";
        }
        OffsetDateTime from = rect.getObservationStartAt();
        OffsetDateTime now = timeService.now();
        long exceeded = readingRepository.countByRoomCodeAndExceededAndRecordedAtBetween(
                rect.getRoomCode(), true, from, now);
        long complaints = complaintRepository.countByRoomCodeAndCreatedAtBetween(rect.getRoomCode(), from, now);
        long overflow = alertRepository.countByRoomCodeAndTypeInAndCreatedAtBetween(
                rect.getRoomCode(), OVERFLOW_TYPES, from, now);
        return "观察期超标" + exceeded + "次、投诉" + complaints + "起、满溢" + overflow + "次";
    }

    private Double avgOdor(String roomCode, OffsetDateTime from, OffsetDateTime to) {
        List<OdorReading> list = readingRepository
                .findByRoomCodeAndRecordedAtBetweenOrderByRecordedAtAsc(roomCode, from, to);
        if (list.isEmpty()) return null;
        double avg = list.stream().mapToInt(OdorReading::getOdorValue).average().orElse(0);
        return Math.round(avg * 10) / 10.0;
    }

    /** 整改状态广播到 governance-events，街道端订阅后可同步责任人与复查时间 */
    private void publish(OdorRectification rect, String event, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("rectNo", rect.getRectNo());
        payload.put("roomCode", rect.getRoomCode());
        payload.put("status", rect.getStatus().name());
        payload.put("round", rect.getRound());
        payload.put("ownerName", rect.getOwnerName());
        payload.put("reviewAt", rect.getReviewAt() == null ? null : rect.getReviewAt().toString());
        payload.put("message", message);
        eventPublisher.publish(rect.getRoomCode(), payload);
    }
}
