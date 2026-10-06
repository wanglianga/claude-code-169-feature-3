package com.city.garbage.service;

import com.city.garbage.api.dto.RectificationReportRequest;
import com.city.garbage.config.LinkageProperties;
import com.city.garbage.domain.*;
import com.city.garbage.repo.*;
import com.city.garbage.support.BusinessException;
import com.city.garbage.support.TimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异味反复整改：
 * 1) 设备每次上报异味值都记入异味曲线；一周内多次超标自动生成整改单，
 *    派生消杀、桶体清洗、排风设备检查、清运频次复盘四类任务（挂治理联动单）；
 * 2) 物业完成整改后上传照片与设备读数，并登记整改责任人与复查时间（同步街道端），整改单进入观察期；
 * 3) 观察期内持续记录异味曲线，街道端可查看异味是否下降、投诉与满溢次数；
 * 4) 复查到期评估：无复发则“整改有效”闭环；清洗后仍超标则升级下一轮，
 *    建议检查排风、桶体密封或清运频次，而不是只重复消杀。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OdorRectificationService {

    private final OdorRectificationRepository rectificationRepository;
    private final RectificationReportRepository reportRepository;
    private final OdorReadingRepository odorReadingRepository;
    private final GarbageRoomRepository roomRepository;
    private final AlertRepository alertRepository;
    private final ComplaintRepository complaintRepository;
    private final WorkTaskRepository taskRepository;
    private final LinkageEngine engine;
    private final LinkageProperties props;
    private final TimeService timeService;

    private static final List<RectificationStatus> ACTIVE_STATUSES =
            List.of(RectificationStatus.OPEN, RectificationStatus.VERIFYING);

    /** 整改反馈时自动完成的现场任务类型（含普通保洁） */
    private static final List<TaskType> RECTIFICATION_TASK_TYPES = List.of(
            TaskType.DISINFECTION, TaskType.CLEANING, TaskType.BIN_DEEP_CLEAN,
            TaskType.EXHAUST_CHECK, TaskType.COLLECTION_REVIEW);

    // ---------- 读数与触发 ----------

    /** 记录异味曲线点（设备上报或整改反馈读数） */
    @Transactional
    public OdorReading recordReading(String roomCode, int value, ReadingSource source, Long rectificationId) {
        OdorReading r = new OdorReading();
        r.setRoomCode(roomCode);
        r.setValue(value);
        r.setSource(source);
        r.setRectificationId(rectificationId);
        r.setRecordedAt(timeService.now());
        return odorReadingRepository.save(r);
    }

    /**
     * 异味超标时调用：统计一周窗口内超标次数，达到阈值且当前无进行中整改单时，
     * 生成新一轮异味整改单（告警并入垃圾房联动单，派生四类整改任务）。
     */
    @Transactional
    public void onOdorExceedance(GarbageRoom room, int odorValue) {
        OffsetDateTime since = timeService.now().minusDays(props.getOdorRecurringWindowDays());
        long exceedances = odorReadingRepository.countByRoomCodeAndValueGreaterThanEqualAndRecordedAtAfter(
                room.getCode(), props.getOdorThreshold(), since);
        if (exceedances < props.getOdorRecurringCount()) return;
        if (rectificationRepository.existsByRoomCodeAndStatusIn(room.getCode(), ACTIVE_STATUSES)) return;

        int round = (int) rectificationRepository.countByRoomCode(room.getCode()) + 1;
        String suggestion = round == 1
                ? "一周内异味超标" + exceedances + "次：请完成消杀、桶体深度清洗、排风设备检查与清运频次复盘，"
                  + "整改后上传现场照片与设备读数"
                : escalatedSuggestion(round, exceedances, 0);
        createRound(room, round, (int) exceedances, suggestion, round > 1);
    }

    // ---------- 整改反馈 ----------

    /**
     * 物业提交整改反馈：照片 + 设备读数 + 整改责任人 + 复查时间。
     * 反馈后整改单进入观察期，整改任务自动完成、反复超标告警解除，责任人与复查时间同步街道端。
     */
    @Transactional
    public OdorRectification submitReport(Long rectificationId, RectificationReportRequest req) {
        OdorRectification rect = rectificationRepository.findById(rectificationId)
                .orElseThrow(() -> new BusinessException("异味整改单不存在: " + rectificationId));
        if (rect.getStatus() != RectificationStatus.OPEN) {
            throw new BusinessException("整改单当前状态(" + rect.getStatus() + ")不可提交整改反馈");
        }
        OffsetDateTime now = timeService.now();

        RectificationReport report = new RectificationReport();
        report.setRectificationId(rect.getId());
        report.setPhotoUrls(req.photoUrls() == null ? null : String.join(",", req.photoUrls()));
        report.setOdorReading(req.odorReading());
        report.setExhaustReading(req.exhaustReading());
        report.setNote(req.note());
        report.setCreatedBy(req.assigneeName());
        report.setCreatedAt(now);
        reportRepository.save(report);

        // 整改后设备读数记入异味曲线，街道端可对比整改前后
        recordReading(rect.getRoomCode(), req.odorReading(), ReadingSource.RECTIFICATION_REPORT, rect.getId());

        rect.setAssigneeName(req.assigneeName());
        rect.setReviewAt(req.reviewAt() != null ? req.reviewAt() : now.plusHours(props.getOdorReviewHours()));
        rect.setReportedAt(now);
        rect.setStatus(RectificationStatus.VERIFYING);
        rect.setUpdatedAt(now);
        rectificationRepository.save(rect);

        // 整改完成：联动单上消杀/保洁/桶体清洗/排风检查/清运复盘任务一并完成
        if (rect.getCaseNo() != null) {
            for (WorkTask t : taskRepository.findByCaseNo(rect.getCaseNo())) {
                if (RECTIFICATION_TASK_TYPES.contains(t.getType()) && t.getStatus() != TaskStatus.DONE) {
                    engine.markDone(t, "整改反馈已提交（责任人 " + req.assigneeName() + "）："
                            + (req.note() == null ? "" : req.note()));
                }
            }
        }
        engine.resolveByType(rect.getRoomCode(), AlertType.ODOR_RECURRING,
                "第" + rect.getRoundNo() + "轮整改已反馈，进入观察期至 " + rect.getReviewAt());
        if (req.odorReading() < props.getOdorThreshold()) {
            engine.resolveByType(rect.getRoomCode(), AlertType.ODOR,
                    "整改后异味读数回落至 " + req.odorReading());
        }
        log.info("异味整改反馈 room={} round={} 责任人={} 复查时间={}",
                rect.getRoomCode(), rect.getRoundNo(), req.assigneeName(), rect.getReviewAt());
        return rect;
    }

    // ---------- 到期评估（定时巡检） ----------

    /**
     * 复查时间到期的观察期整改单逐一评估：
     * 观察期内无异味超标且无异味投诉 -> 整改有效闭环；
     * 仍有超标/投诉 -> 闭环为“已升级”并生成下一轮整改，
     * 建议检查排风设备、桶体密封或清运频次，而不是只重复消杀。
     */
    @Transactional
    public int evaluateDue() {
        OffsetDateTime now = timeService.now();
        int evaluated = 0;
        for (OdorRectification rect : rectificationRepository
                .findByStatusAndReviewAtBefore(RectificationStatus.VERIFYING, now)) {
            Map<String, Long> stats = statsSince(rect.getRoomCode(), rect.getReportedAt());
            long exceed = stats.get("odorExceedances");
            long complaints = stats.get("odorComplaints");
            long overflow = stats.get("overflowCount");
            rect.setClosedAt(now);
            rect.setUpdatedAt(now);
            if (exceed == 0 && complaints == 0) {
                rect.setStatus(RectificationStatus.CLOSED_IMPROVED);
                rect.setOutcomeNote("观察期内异味无超标、无投诉（满溢" + overflow + "次），整改有效");
                rectificationRepository.save(rect);
                log.info("异味整改有效闭环 room={} round={}", rect.getRoomCode(), rect.getRoundNo());
            } else {
                rect.setStatus(RectificationStatus.CLOSED_ESCALATED);
                rect.setOutcomeNote("观察期内仍超标" + exceed + "次、投诉" + complaints + "起、满溢" + overflow + "次");
                rectificationRepository.save(rect);
                GarbageRoom room = roomRepository.findByCode(rect.getRoomCode()).orElse(null);
                if (room != null) {
                    int round = (int) rectificationRepository.countByRoomCode(rect.getRoomCode()) + 1;
                    createRound(room, round, (int) exceed,
                            escalatedSuggestion(round, exceed, complaints), true);
                }
                log.info("异味整改后仍超标，升级下一轮 room={} round={} 超标={} 投诉={}",
                        rect.getRoomCode(), rect.getRoundNo(), exceed, complaints);
            }
            evaluated++;
        }
        return evaluated;
    }

    // ---------- 街道端观察 ----------

    /** 观察期统计：自整改反馈以来的异味超标次数、异味投诉数、满溢次数 */
    public Map<String, Long> statsSince(String roomCode, OffsetDateTime since) {
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("odorExceedances", odorReadingRepository
                .countByRoomCodeAndValueGreaterThanEqualAndRecordedAtAfter(roomCode, props.getOdorThreshold(), since));
        stats.put("odorComplaints", complaintRepository
                .countByRoomCodeAndTypeAndCreatedAtAfter(roomCode, "ODOR", since));
        stats.put("overflowCount", alertRepository.countByRoomCodeAndTypeInAndCreatedAtAfter(roomCode,
                List.of(AlertType.KITCHEN_FULL_NO_TRUCK, AlertType.OTHER_BIN_FULL), since));
        return stats;
    }

    /** 垃圾房异味曲线（街道端观察异味是否下降） */
    public List<Map<String, Object>> odorCurve(String roomCode, int days) {
        OffsetDateTime now = timeService.now();
        List<Map<String, Object>> curve = new ArrayList<>();
        for (OdorReading r : odorReadingRepository.findByRoomCodeAndRecordedAtBetweenOrderByRecordedAtAsc(
                roomCode, now.minusDays(days), now)) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("recordedAt", r.getRecordedAt().toString());
            point.put("value", r.getValue());
            point.put("source", r.getSource().name());
            point.put("exceeded", r.getValue() >= props.getOdorThreshold());
            curve.add(point);
        }
        return curve;
    }

    /** 整改单详情：整改信息 + 回执照片读数 + 异味曲线 + 整改前后对比 + 观察期统计 */
    public Map<String, Object> detailView(Long rectificationId) {
        OdorRectification rect = rectificationRepository.findById(rectificationId)
                .orElseThrow(() -> new BusinessException("异味整改单不存在: " + rectificationId));
        OffsetDateTime now = timeService.now();
        List<OdorReading> readings = odorReadingRepository
                .findByRoomCodeAndRecordedAtBetweenOrderByRecordedAtAsc(
                        rect.getRoomCode(), rect.getCreatedAt().minusDays(1), now);

        OffsetDateTime splitAt = rect.getReportedAt() != null ? rect.getReportedAt() : now;
        Double avgBefore = avg(readings, splitAt, true);
        Double avgAfter = avg(readings, splitAt, false);

        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("odorThreshold", props.getOdorThreshold());
        trend.put("avgBeforeReport", avgBefore);
        trend.put("avgAfterReport", avgAfter);
        trend.put("improved", avgBefore != null && avgAfter != null ? avgAfter < avgBefore : null);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("rectification", rect);
        view.put("reports", reportRepository.findByRectificationIdOrderByCreatedAtAsc(rect.getId()));
        view.put("odorCurve", readings.stream().map(this::toCurvePoint).toList());
        view.put("trend", trend);
        Map<String, Long> stats = statsSince(rect.getRoomCode(), splitAt);
        view.put("postReportStats", stats);
        view.put("statsSince", splitAt.toString());
        return view;
    }

    /** 物业端：进行中的整改单（待整改/观察期） */
    public List<OdorRectification> activeList() {
        return rectificationRepository.findByStatusInOrderByCreatedAtDesc(ACTIVE_STATUSES);
    }

    /** 街道端：整改单列表（含责任人与复查时间），可按垃圾房过滤 */
    public List<OdorRectification> listForStreet(String roomCode) {
        if (roomCode == null || roomCode.isBlank()) {
            return rectificationRepository.findAllByOrderByCreatedAtDesc();
        }
        return rectificationRepository.findByRoomCodeOrderByCreatedAtDesc(roomCode);
    }

    // ---------- 内部 ----------

    /** 生成一轮整改：反复超标告警并入联动单派生四类任务；升级轮次调整任务优先级与说明 */
    private void createRound(GarbageRoom room, int round, int triggerCount, String suggestion, boolean escalated) {
        Alert alert = engine.raiseAlert(room, AlertType.ODOR_RECURRING,
                escalated ? AlertSeverity.URGENT : AlertSeverity.HIGH,
                "一周内多次异味超标，启动第" + round + "轮异味整改",
                "ODOR_RECURRING:" + room.getCode(), null, null);

        OdorRectification rect = new OdorRectification();
        rect.setRoomCode(room.getCode());
        rect.setCommunityId(room.getCommunityId());
        rect.setCaseNo(alert.getCaseNo());
        rect.setRoundNo(round);
        rect.setTriggerCount(triggerCount);
        rect.setSuggestion(suggestion);
        rect.setStatus(RectificationStatus.OPEN);
        rect.setCreatedAt(timeService.now());
        rect.setUpdatedAt(timeService.now());
        rectificationRepository.save(rect);

        if (escalated) applyEscalatedTaskPlan(alert.getCaseNo());
        log.info("异味反复整改单生成 room={} round={} case={} 超标次数={}",
                room.getCode(), round, alert.getCaseNo(), triggerCount);
    }

    /**
     * 升级轮次的任务计划：重点排查排风设备、桶体密封与清运频次，
     * 消杀仅作辅助手段，避免“只重复消杀”。
     */
    private void applyEscalatedTaskPlan(String caseNo) {
        for (WorkTask t : taskRepository.findByCaseNo(caseNo)) {
            if (t.getStatus() == TaskStatus.DONE) continue;
            switch (t.getType()) {
                case EXHAUST_CHECK -> {
                    t.setPriority(90);
                    t.setDetail("【升级】重点检查排风设备运行状态与风量是否达标");
                }
                case COLLECTION_REVIEW -> {
                    t.setPriority(85);
                    t.setDetail("【升级】复盘清运频次，评估是否增加清运班次");
                }
                case BIN_DEEP_CLEAN -> {
                    t.setPriority(80);
                    t.setDetail("【升级】检查桶体密封条是否老化，并深度清洗桶体");
                }
                case DISINFECTION -> {
                    t.setPriority(50);
                    t.setDetail("【升级】辅助消杀（已多次消杀仍超标，非唯一手段）");
                }
                default -> { }
            }
            taskRepository.save(t);
        }
    }

    private String escalatedSuggestion(int round, long exceedances, long complaints) {
        return "上一轮整改（含消杀/清洗）后仍超标" + exceedances + "次"
                + (complaints > 0 ? "、异味投诉" + complaints + "起" : "")
                + "：建议重点检查排风设备运行与风量、桶体密封条老化情况、清运频次是否不足，"
                + "避免仅重复消杀（第" + round + "轮整改）";
    }

    private Double avg(List<OdorReading> readings, OffsetDateTime splitAt, boolean before) {
        return readings.stream()
                .filter(r -> before ? r.getRecordedAt().isBefore(splitAt) : !r.getRecordedAt().isBefore(splitAt))
                .mapToInt(OdorReading::getValue)
                .average().stream().boxed().findFirst()
                .map(v -> Math.round(v * 100.0) / 100.0)
                .orElse(null);
    }

    private Map<String, Object> toCurvePoint(OdorReading r) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("recordedAt", r.getRecordedAt().toString());
        point.put("value", r.getValue());
        point.put("source", r.getSource().name());
        point.put("exceeded", r.getValue() >= props.getOdorThreshold());
        return point;
    }
}
