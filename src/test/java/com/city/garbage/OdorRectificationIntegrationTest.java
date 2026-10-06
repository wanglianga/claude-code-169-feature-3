package com.city.garbage;

import com.city.garbage.domain.*;
import com.city.garbage.repo.*;
import com.city.garbage.service.LinkageEngine;
import com.city.garbage.service.OdorRectificationService;
import com.city.garbage.support.TimeService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异味反复整改全链路集成测试（H2 + 同步事件总线）。
 * 覆盖：一周内多次异味超标生成整改单（消杀/桶体清洗/排风检查/清运复盘四类任务）；
 * 物业整改后上传照片与设备读数、责任人与复查时间同步街道端；街道观察异味曲线/投诉/满溢；
 * 清洗后仍超标 -> 升级下一轮并建议排风/桶体密封/清运频次而非重复消杀；无复发 -> 整改有效闭环。
 *
 * 使用专用垃圾房 ROOM8/ROOM9，避免与其他测试类共享状态；结束后清理房间实时字段与告警。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OdorRectificationIntegrationTest {

    @Autowired TestRestTemplate http;
    @Autowired TimeService timeService;
    @Autowired GarbageRoomRepository roomRepository;
    @Autowired CommunityRepository communityRepository;
    @Autowired AlertRepository alertRepository;
    @Autowired WorkTaskRepository taskRepository;
    @Autowired OdorRectificationRepository rectificationRepository;
    @Autowired OdorRectificationService odorRectificationService;
    @Autowired LinkageEngine engine;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2026-10-05T04:00:00Z");

    @BeforeEach
    void setUp() {
        clockAt(T0);
        ensureRoom("ROOM9", "阳光花园9号垃圾房(异味整改测试)");
        ensureRoom("ROOM8", "阳光花园8号垃圾房(异味整改测试)");
    }

    @AfterEach
    void resetClock() {
        timeService.setClock(Clock.system(ZONE));
    }

    /** 收尾：解除专用房全部告警、复位实时字段，避免影响其他测试类（共享同一 H2 上下文） */
    @AfterAll
    void cleanupRooms() {
        timeService.setClock(Clock.system(ZONE));
        for (String code : List.of("ROOM8", "ROOM9")) {
            for (AlertType t : List.of(AlertType.ODOR, AlertType.ODOR_RECURRING, AlertType.ODOR_COMPLAINT,
                    AlertType.KITCHEN_FULL_NO_TRUCK, AlertType.OTHER_BIN_FULL)) {
                engine.resolveByType(code, t, "测试收尾清理");
            }
            roomRepository.findByCode(code).ifPresent(r -> {
                r.setKitchenCapacityPct(0);
                r.setOtherCapacityPct(0);
                r.setKitchenFull(false);
                r.setKitchenFullSince(null);
                r.setOtherFullSince(null);
                r.setCurrentOdor(0);
                r.setTruckPresent(false);
                r.setOnline(true);
                r.setLastHeartbeatAt(timeService.now());
                roomRepository.save(r);
            });
        }
    }

    private void clockAt(Instant instant) {
        timeService.setClock(Clock.fixed(instant, ZONE));
    }

    private void ensureRoom(String code, String name) {
        roomRepository.findByCode(code).orElseGet(() -> {
            GarbageRoom r = new GarbageRoom();
            r.setCode(code);
            r.setName(name);
            r.setCommunityId(communityRepository.findByCode("C001").orElseThrow().getId());
            r.setAddress("异味整改测试点");
            r.setOnline(true);
            r.setLastHeartbeatAt(timeService.now());
            return roomRepository.save(r);
        });
    }

    private HttpHeaders json() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private JsonNode post(String url, Object body) {
        ResponseEntity<JsonNode> resp = http.exchange(url, HttpMethod.POST,
                new HttpEntity<>(body, json()), JsonNode.class);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("POST %s -> %s %s", url, resp.getStatusCode(), resp.getBody()).isTrue();
        return resp.getBody();
    }

    private JsonNode get(String url) {
        ResponseEntity<JsonNode> resp = http.getForEntity(url, JsonNode.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).as("GET %s -> %s", url, resp.getStatusCode()).isTrue();
        return resp.getBody();
    }

    private Map<String, Object> event(String type, String room, Map<String, Object> extra) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("eventType", type);
        m.put("roomCode", room);
        m.put("deviceCode", "DEV-" + type);
        m.put("occurredAt", timeService.now().toString());
        if (extra != null) m.putAll(extra);
        return m;
    }

    private long openAlerts(String room, AlertType type) {
        return alertRepository.findByRoomCodeAndStatusIn(room,
                        List.of(AlertStatus.OPEN, AlertStatus.LINKED)).stream()
                .filter(a -> a.getType() == type).count();
    }

    private OdorRectification latestRectification(String room) {
        List<OdorRectification> list = rectificationRepository.findByRoomCodeOrderByCreatedAtDesc(room);
        assertThat(list).as("房间 %s 应存在异味整改单", room).isNotEmpty();
        return list.get(0);
    }

    // ---------- 1. 一周内多次异味超标 -> 生成整改单 + 消杀/桶体清洗/排风检查/清运复盘四类任务 ----------
    @Test @Order(1)
    void recurringOdorTriggersRectificationWithFourTasks() {
        clockAt(T0);
        post("/api/device/events", event("ODOR", "ROOM9", Map.of("odorValue", 75)));
        post("/api/device/events", event("ODOR", "ROOM9", Map.of("odorValue", 80)));
        // 前两次超标仅产生普通异味告警，不应生成整改单
        assertThat(rectificationRepository.findByRoomCodeOrderByCreatedAtDesc("ROOM9")).isEmpty();
        post("/api/device/events", event("ODOR", "ROOM9", Map.of("odorValue", 85)));

        // 一周内第 3 次超标 -> 生成第 1 轮整改单
        OdorRectification rect = latestRectification("ROOM9");
        assertThat(rect.getStatus()).isEqualTo(RectificationStatus.OPEN);
        assertThat(rect.getRoundNo()).isEqualTo(1);
        assertThat(rect.getTriggerCount()).isEqualTo(3);
        assertThat(rect.getSuggestion()).contains("消杀").contains("桶体").contains("排风").contains("清运频次");
        assertThat(openAlerts("ROOM9", AlertType.ODOR_RECURRING)).isEqualTo(1);

        // 整改任务挂在同一联动单上：消杀、桶体清洗、排风设备检查、清运频次复盘
        List<WorkTask> tasks = taskRepository.findByCaseNo(rect.getCaseNo());
        assertThat(tasks).extracting(WorkTask::getType).contains(
                TaskType.DISINFECTION, TaskType.BIN_DEEP_CLEAN,
                TaskType.EXHAUST_CHECK, TaskType.COLLECTION_REVIEW);

        // 物业端能看到进行中的整改单
        JsonNode active = get("/api/property/odor-rectifications");
        boolean found = false;
        for (JsonNode n : active) {
            if (n.get("id").asLong() == rect.getId()) found = true;
        }
        assertThat(found).isTrue();
    }

    // ---------- 2. 物业整改后上传照片与设备读数 -> 观察期；责任人与复查时间同步街道端 ----------
    @Test @Order(2)
    void reportEntersVerifyingAndStreetSeesAssigneeAndReviewTime() {
        clockAt(T0.plusSeconds(600)); // 整改反馈晚于超标上报
        OdorRectification rect = latestRectification("ROOM9");
        String reviewAt = timeService.now().plusHours(48).toString();

        JsonNode resp = post("/api/property/odor-rectifications/" + rect.getId() + "/report", Map.of(
                "assigneeName", "张师傅",
                "odorReading", 45,
                "exhaustReading", 12,
                "photoUrls", List.of("http://img.example/rectify1.jpg", "http://img.example/rectify2.jpg"),
                "note", "已完成消杀、桶体深度清洗与排风检修",
                "reviewAt", reviewAt));
        assertThat(resp.get("status").asText()).isEqualTo("VERIFYING");
        assertThat(resp.get("assigneeName").asText()).isEqualTo("张师傅");
        assertThat(resp.get("reviewAt")).isNotNull();

        // 整改任务全部完成，反复超标告警解除，联动单闭环释放
        for (WorkTask t : taskRepository.findByCaseNo(rect.getCaseNo())) {
            assertThat(t.getStatus()).as("任务 %s 应已完成", t.getType()).isEqualTo(TaskStatus.DONE);
        }
        assertThat(openAlerts("ROOM9", AlertType.ODOR_RECURRING)).isZero();
        assertThat(openAlerts("ROOM9", AlertType.ODOR)).isZero();
        assertThat(roomRepository.findByCode("ROOM9").orElseThrow().getActiveCaseNo()).isNull();

        // 街道端同步看到整改责任人与复查时间
        JsonNode streetList = get("/api/street/odor-rectifications?roomCode=ROOM9");
        assertThat(streetList).hasSize(1);
        assertThat(streetList.get(0).get("assigneeName").asText()).isEqualTo("张师傅");
        assertThat(streetList.get(0).get("reviewAt")).isNotNull();
        assertThat(streetList.get(0).get("status").asText()).isEqualTo("VERIFYING");

        // 整改回执含照片与设备读数
        JsonNode detail = get("/api/street/odor-rectifications/" + rect.getId());
        JsonNode reports = detail.get("reports");
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).get("photoUrls").asText()).contains("rectify1.jpg");
        assertThat(reports.get(0).get("odorReading").asInt()).isEqualTo(45);
        assertThat(reports.get(0).get("exhaustReading").asInt()).isEqualTo(12);
    }

    // ---------- 3. 观察期：街道持续观察异味曲线、投诉与满溢次数 ----------
    @Test @Order(3)
    void streetObservesOdorCurveComplaintsAndOverflow() {
        clockAt(T0.plusSeconds(1200)); // 观察期内
        OdorRectification rect = latestRectification("ROOM9");

        // 整改后异味读数持续回落
        post("/api/device/events", event("ODOR", "ROOM9", Map.of("odorValue", 40)));
        post("/api/device/events", event("ODOR", "ROOM9", Map.of("odorValue", 38)));
        // 观察期内发生一起异味投诉、一次厨余满溢
        post("/api/resident/complaints", Map.of(
                "contact", "13800000000", "roomCode", "ROOM9", "type", "ODOR",
                "content", "垃圾房附近仍有味道，请复查"));
        post("/api/device/events", event("KITCHEN_FULL", "ROOM9",
                Map.of("binType", "KITCHEN", "capacityPct", 95)));

        JsonNode detail = get("/api/street/odor-rectifications/" + rect.getId());
        // 异味曲线包含整改前超标读数与整改后回落读数
        JsonNode curve = detail.get("odorCurve");
        assertThat(curve.size()).isGreaterThanOrEqualTo(6);
        // 整改前均值 80（75/80/85），整改后均值 41（45/40/38）-> 异味下降
        assertThat(detail.get("trend").get("avgBeforeReport").asDouble()).isEqualTo(80.0);
        assertThat(detail.get("trend").get("avgAfterReport").asDouble()).isEqualTo(41.0);
        assertThat(detail.get("trend").get("improved").asBoolean()).isTrue();
        // 观察期统计：投诉 1 起、满溢 1 次、无新增超标
        JsonNode stats = detail.get("postReportStats");
        assertThat(stats.get("odorComplaints").asInt()).isEqualTo(1);
        assertThat(stats.get("overflowCount").asInt()).isEqualTo(1);
        assertThat(stats.get("odorExceedances").asInt()).isEqualTo(0);

        // 垃圾房异味曲线接口
        JsonNode roomCurve = get("/api/street/rooms/ROOM9/odor-curve?days=7");
        assertThat(roomCurve.size()).isGreaterThanOrEqualTo(6);
    }

    // ---------- 4. 清洗后仍超标 -> 升级下一轮，建议排风/桶体密封/清运频次而非重复消杀 ----------
    @Test @Order(4)
    void stillExceedingAfterCleaningEscalatesWithTargetedSuggestion() {
        clockAt(T0.plusSeconds(1800));
        OdorRectification round1 = latestRectification("ROOM9");

        // 观察期内再次超标；整改单处于观察期，不会重复开单
        post("/api/device/events", event("ODOR", "ROOM9", Map.of("odorValue", 78)));
        assertThat(rectificationRepository.findByRoomCodeOrderByCreatedAtDesc("ROOM9")).hasSize(1);

        // 复查时间到期，定时巡检评估
        round1.setReviewAt(timeService.now().minusMinutes(1));
        rectificationRepository.save(round1);
        int evaluated = odorRectificationService.evaluateDue();
        assertThat(evaluated).isEqualTo(1);

        // 第 1 轮闭环为“已升级”
        OdorRectification closed = rectificationRepository.findById(round1.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(RectificationStatus.CLOSED_ESCALATED);
        assertThat(closed.getOutcomeNote()).contains("超标1次").contains("投诉1起");

        // 生成第 2 轮整改，建议聚焦排风、桶体密封、清运频次，而不是只重复消杀
        OdorRectification round2 = latestRectification("ROOM9");
        assertThat(round2.getRoundNo()).isEqualTo(2);
        assertThat(round2.getStatus()).isEqualTo(RectificationStatus.OPEN);
        assertThat(round2.getSuggestion())
                .contains("排风").contains("密封").contains("清运频次").contains("重复消杀");

        // 升级轮次任务优先级：排风检查最优先，消杀降级为辅助
        List<WorkTask> tasks = taskRepository.findByCaseNo(round2.getCaseNo());
        WorkTask exhaust = tasks.stream().filter(t -> t.getType() == TaskType.EXHAUST_CHECK).findFirst().orElseThrow();
        WorkTask disinfection = tasks.stream().filter(t -> t.getType() == TaskType.DISINFECTION).findFirst().orElseThrow();
        WorkTask collectionReview = tasks.stream().filter(t -> t.getType() == TaskType.COLLECTION_REVIEW).findFirst().orElseThrow();
        WorkTask binClean = tasks.stream().filter(t -> t.getType() == TaskType.BIN_DEEP_CLEAN).findFirst().orElseThrow();
        assertThat(exhaust.getPriority()).isEqualTo(90);
        assertThat(collectionReview.getPriority()).isEqualTo(85);
        assertThat(binClean.getPriority()).isEqualTo(80);
        assertThat(disinfection.getPriority()).isEqualTo(50);
        assertThat(exhaust.getPriority()).isGreaterThan(disinfection.getPriority());
        assertThat(disinfection.getDetail()).contains("非唯一手段");
    }

    // ---------- 5. 观察期无复发 -> 整改有效闭环 ----------
    @Test @Order(5)
    void noRecurrenceClosesImproved() {
        clockAt(T0);
        post("/api/device/events", event("ODOR", "ROOM8", Map.of("odorValue", 75)));
        post("/api/device/events", event("ODOR", "ROOM8", Map.of("odorValue", 82)));
        post("/api/device/events", event("ODOR", "ROOM8", Map.of("odorValue", 88)));
        OdorRectification rect = latestRectification("ROOM8");
        assertThat(rect.getStatus()).isEqualTo(RectificationStatus.OPEN);

        // 物业整改反馈（复查时间设为过去，便于立即评估）
        clockAt(T0.plusSeconds(600));
        post("/api/property/odor-rectifications/" + rect.getId() + "/report", Map.of(
                "assigneeName", "李师傅",
                "odorReading", 42,
                "photoUrls", List.of("http://img.example/room8-rectify.jpg"),
                "note", "已消杀并完成桶体清洗",
                "reviewAt", T0.plusSeconds(300).toString()));

        // 观察期内只有回落读数，无超标、无投诉
        clockAt(T0.plusSeconds(900));
        post("/api/device/events", event("ODOR", "ROOM8", Map.of("odorValue", 40)));

        clockAt(T0.plusSeconds(1200));
        int evaluated = odorRectificationService.evaluateDue();
        assertThat(evaluated).isEqualTo(1);

        OdorRectification closed = rectificationRepository.findById(rect.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(RectificationStatus.CLOSED_IMPROVED);
        assertThat(closed.getOutcomeNote()).contains("整改有效");
        // 未升级：ROOM8 只有这一轮整改
        assertThat(rectificationRepository.findByRoomCodeOrderByCreatedAtDesc("ROOM8")).hasSize(1);

        JsonNode detail = get("/api/street/odor-rectifications/" + rect.getId());
        assertThat(detail.get("postReportStats").get("odorExceedances").asInt()).isEqualTo(0);
        assertThat(detail.get("trend").get("improved").asBoolean()).isTrue();
    }
}
