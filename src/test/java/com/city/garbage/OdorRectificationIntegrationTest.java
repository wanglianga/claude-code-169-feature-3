package com.city.garbage;

import com.city.garbage.domain.*;
import com.city.garbage.repo.*;
import com.city.garbage.service.OdorRectificationService;
import com.city.garbage.support.TimeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异味反复整改全链路集成测试（H2 + 同步事件总线）。
 * 覆盖：一周内多次超标自动开整改单（消杀/桶体清洗/排风检查/清运复盘）、
 * 物业上传照片与设备读数完成整改进入观察期、责任人与复查时间同步街道端、
 * 观察期再超标升级深度排查（建议排风/桶体密封/清运频次而非重复消杀）、
 * 观察期到期评估闭环、整改后读数仍超标直接升级。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OdorRectificationIntegrationTest {

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper om;
    @Autowired TimeService timeService;
    @Autowired GarbageRoomRepository roomRepository;
    @Autowired WorkTaskRepository taskRepository;
    @Autowired OdorRectificationRepository rectRepository;
    @Autowired OdorReadingRepository readingRepository;
    @Autowired OdorRectificationService rectificationService;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2026-10-05T04:00:00Z"); // 北京时间 12:00

    @AfterEach
    void resetClock() {
        timeService.setClock(Clock.system(ZONE));
    }

    @BeforeEach
    void fixedClock() {
        timeService.setClock(Clock.fixed(T0, ZONE));
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

    private void odor(String room, int value) {
        post("/api/device/events", Map.of(
                "eventType", "ODOR", "roomCode", room, "deviceCode", "DEV-ODOR",
                "odorValue", value, "occurredAt", timeService.now().toString()));
    }

    private String activeRectNo(String room) {
        List<OdorRectification> list = rectRepository.findByRoomCodeAndStatusIn(room, List.of(
                OdorRectificationStatus.RECTIFYING,
                OdorRectificationStatus.OBSERVING,
                OdorRectificationStatus.ESCALATED));
        assertThat(list).as("room %s 应有一张进行中的整改单", room).hasSize(1);
        return list.get(0).getRectNo();
    }

    private void completeAllRectTasks(String rectNo) {
        for (WorkTask t : taskRepository.findByRectNo(rectNo)) {
            if (t.getStatus() != TaskStatus.DONE) {
                post("/api/property/tasks/" + t.getId() + "/complete", Map.of("note", "已完成 " + t.getType()));
            }
        }
    }

    // ---------- 1. 一周内多次异味超标 -> 自动开整改单，生成消杀/桶体清洗/排风检查/清运复盘四类任务 ----------
    @Test @Order(1)
    void repeatedOdorExceedance_opensRectificationWithFourTasks() {
        odor("ROOM3", 75);
        odor("ROOM3", 80);
        // 2 次未达阈值（默认 3 次），不开单
        assertThat(rectRepository.findByRoomCodeOrderByCreatedAtDesc("ROOM3")).isEmpty();

        odor("ROOM3", 85);
        String rectNo = activeRectNo("ROOM3");

        OdorRectification rect = rectRepository.findByRectNo(rectNo).orElseThrow();
        assertThat(rect.getStatus()).isEqualTo(OdorRectificationStatus.RECTIFYING);
        assertThat(rect.getTriggerCount()).isEqualTo(3);
        assertThat(rect.getWindowDays()).isEqualTo(7);
        assertThat(rect.getRound()).isEqualTo(1);

        JsonNode detail = get("/api/property/odor-rectifications/" + rectNo);
        JsonNode tasks = detail.get("tasks");
        assertThat(tasks).hasSize(4);
        assertThat(tasks.findValuesAsText("type")).containsExactlyInAnyOrder(
                "DISINFECTION", "BIN_WASH", "VENTILATION_CHECK", "COLLECTION_REVIEW");
        // 物业待办里能看到桶体清洗等新任务
        JsonNode pending = get("/api/property/tasks/pending");
        assertThat(pending.findValuesAsText("type")).contains("BIN_WASH", "VENTILATION_CHECK");

        // 再超标不重复开单
        odor("ROOM3", 90);
        assertThat(rectRepository.findByRoomCodeOrderByCreatedAtDesc("ROOM3")).hasSize(1);
    }

    // ---------- 2. 物业上传照片+设备读数完成整改 -> 观察期；责任人与复查时间同步街道端 ----------
    @Test @Order(2)
    void completeRectification_entersObservation_streetSeesOwnerAndReviewTime() {
        String rectNo = activeRectNo("ROOM3");

        // 任务未完成时不允许提交整改结果
        ResponseEntity<JsonNode> tooEarly = http.exchange(
                "/api/property/odor-rectifications/" + rectNo + "/complete", HttpMethod.POST,
                new HttpEntity<>(Map.of("ownerName", "陈保洁", "photos", List.of("p1.jpg"),
                        "deviceReading", 45), json()), JsonNode.class);
        assertThat(tooEarly.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        completeAllRectTasks(rectNo);

        // 时钟前进 30 分钟，避免与触发读数同刻落入观察窗口
        timeService.setClock(Clock.fixed(T0.plus(30, ChronoUnit.MINUTES), ZONE));
        JsonNode completed = post("/api/property/odor-rectifications/" + rectNo + "/complete",
                Map.of("ownerName", "陈保洁", "photos", List.of("wash1.jpg", "vent1.jpg"),
                        "deviceReading", 45, "note", "已消杀、清洗桶体并检修排风"));
        assertThat(completed.get("status").asText()).isEqualTo("OBSERVING");
        assertThat(completed.get("ownerName").asText()).isEqualTo("陈保洁");
        assertThat(completed.get("reviewAt").asText()).isNotBlank();

        // 街道端同步看到责任人与复查时间
        JsonNode streetList = get("/api/street/odor-rectifications?roomCode=ROOM3");
        assertThat(streetList).hasSize(1);
        JsonNode s0 = streetList.get(0);
        assertThat(s0.get("ownerName").asText()).isEqualTo("陈保洁");
        assertThat(s0.get("reviewAt").asText()).isNotBlank();
        assertThat(s0.get("status").asText()).isEqualTo("OBSERVING");

        // 街道端异味曲线：3 次超标读数 + 整改后上传的 45，整改前后均值可对比
        JsonNode curve = get("/api/street/rooms/ROOM3/odor-curve?days=7");
        assertThat(curve.get("odorThreshold").asInt()).isEqualTo(70);
        assertThat(curve.get("readings").size()).isGreaterThanOrEqualTo(4);
        JsonNode obs = get("/api/street/odor-rectifications/" + rectNo + "/observation");
        assertThat(obs.get("avgOdorBeforeRectification").asDouble()).isGreaterThan(70);
        assertThat(obs.get("avgOdorAfterRectification").asDouble()).isLessThan(70);
        assertThat(obs.get("observationExceededCount").asInt()).isEqualTo(0);
    }

    // ---------- 3. 观察期再超标 -> 升级深度排查：建议排风/桶体密封/清运频次，而非重复消杀 ----------
    @Test @Order(3)
    void exceedanceDuringObservation_escalatesWithDeepCheckSuggestion() {
        String rectNo = activeRectNo("ROOM3");
        odor("ROOM3", 82); // 观察期内再次超标

        OdorRectification rect = rectRepository.findByRectNo(rectNo).orElseThrow();
        assertThat(rect.getStatus()).isEqualTo(OdorRectificationStatus.ESCALATED);
        assertThat(rect.getRound()).isEqualTo(2);
        assertThat(rect.getSuggestion()).contains("排风").contains("密封").contains("清运频次");
        assertThat(rect.getSuggestion()).contains("而非简单重复消杀");

        // 新一轮任务：排风复查、桶体密封、清运复盘；不再派重复消杀
        List<WorkTask> pending = taskRepository.findByRectNo(rectNo).stream()
                .filter(t -> t.getStatus() != TaskStatus.DONE).toList();
        assertThat(pending).extracting(WorkTask::getType).containsExactlyInAnyOrder(
                TaskType.VENTILATION_CHECK, TaskType.BIN_SEAL, TaskType.COLLECTION_REVIEW);
        assertThat(pending).extracting(WorkTask::getType).doesNotContain(TaskType.DISINFECTION);
    }

    // ---------- 4. 深度整改后再观察 -> 到期无超标，整改有效闭环 ----------
    @Test @Order(4)
    void deepRectification_thenObservationExpires_closesEffective() {
        String rectNo = activeRectNo("ROOM3");
        completeAllRectTasks(rectNo);

        timeService.setClock(Clock.fixed(T0.plus(2, ChronoUnit.HOURS), ZONE));
        JsonNode completed = post("/api/property/odor-rectifications/" + rectNo + "/complete",
                Map.of("ownerName", "陈保洁", "photos", List.of("seal1.jpg"),
                        "deviceReading", 40, "note", "已更换密封条并上调清运班次"));
        assertThat(completed.get("status").asText()).isEqualTo("OBSERVING");

        // 快进到复查时间之后（观察期 3 天）
        timeService.setClock(Clock.fixed(T0.plus(4, ChronoUnit.DAYS), ZONE));
        JsonNode eval = post("/api/property/odor-rectifications/evaluate-observations", Map.of());
        assertThat(eval.get("evaluatedCount").asInt()).isGreaterThanOrEqualTo(1);

        OdorRectification rect = rectRepository.findByRectNo(rectNo).orElseThrow();
        assertThat(rect.getStatus()).isEqualTo(OdorRectificationStatus.CLOSED_EFFECTIVE);
        assertThat(rect.getClosedAt()).isNotNull();

        // 街道端观察指标：观察期内无超标、异味均值下降
        JsonNode obs = get("/api/street/odor-rectifications/" + rectNo + "/observation");
        assertThat(obs.get("status").asText()).isEqualTo("CLOSED_EFFECTIVE");
        assertThat(obs.get("observationExceededCount").asInt()).isEqualTo(0);
        assertThat(obs.get("avgOdorAfterRectification").asDouble())
                .isLessThan(obs.get("avgOdorBeforeRectification").asDouble());
    }

    // ---------- 5. 整改后上传读数仍超标 -> 不进入观察期，直接升级深度排查 ----------
    @Test @Order(5)
    void readingStillExceededAfterRectification_escalatesImmediately() {
        odor("ROOM2", 78);
        odor("ROOM2", 81);
        odor("ROOM2", 84);
        String rectNo = activeRectNo("ROOM2");
        completeAllRectTasks(rectNo);

        timeService.setClock(Clock.fixed(T0.plus(1, ChronoUnit.HOURS), ZONE));
        JsonNode completed = post("/api/property/odor-rectifications/" + rectNo + "/complete",
                Map.of("ownerName", "李维修", "photos", List.of("after-wash.jpg"),
                        "deviceReading", 88, "note", "清洗消杀后读数仍高"));
        assertThat(completed.get("status").asText()).isEqualTo("ESCALATED");
        assertThat(completed.get("suggestion").asText()).contains("排风").contains("清运频次");

        // 升级任务已生成（桶体密封检查）
        List<WorkTask> pending = taskRepository.findByRectNo(rectNo).stream()
                .filter(t -> t.getStatus() != TaskStatus.DONE).toList();
        assertThat(pending).extracting(WorkTask::getType).contains(TaskType.BIN_SEAL);
    }
}
