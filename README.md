# 城市智能垃圾房满溢异味投放违规联动服务

面向物业、环卫、街道、居民四类角色的垃圾房现场治理服务。垃圾房设备上报**厨余桶满溢、其他垃圾容量、异味、门禁、摄像头识别、称重、消杀、清运车到场、心跳**数据；居民刷卡/扫码投放时记录**小区、楼栋、垃圾类型、重量、积分与违规提示**。

核心设计目标：**让设备告警真正变成现场治理动作**。满溢、异味、错投往往同时出现，系统不会把每个告警单独派单，而是把同一垃圾房的未闭环告警**合并成一张“治理联动单”**，在单上派生保洁/消杀/清运/维修/复核/整改任务；积分、投诉、整改结果都与真实投放、清运记录一一对应，四端围绕同一份垃圾房状态更新。

针对**异味反复超标**场景，系统在一周内多次超标时自动生成“异味整改单”，派生**消杀、桶体清洗、排风设备检查、清运频次复盘**四类任务；物业整改后上传**现场照片与设备读数**并登记**整改责任人与复查时间**（同步街道端），随后进入观察期：服务持续记录**异味曲线、投诉与满溢次数**，复查到期自动评估——异味下降则闭环“整改有效”；**清洗后仍超标则升级下一轮整改，建议检查排风、桶体密封或清运频次，而不是只重复消杀**。

## 技术栈

- Java 17、Spring Boot 3.3、Spring Data JPA、Bean Validation、Actuator
- PostgreSQL 16（生产）、H2（本地/测试，免外部依赖）
- Kafka 3.7（KRaft，无 Zookeeper；设备事件 `device-events`、联动事件 `governance-events`）
- springdoc-openapi + Swagger UI
- Maven、JUnit5、TestRestTemplate、内嵌 Kafka（spring-kafka-test）

## 一键启动（Docker Compose）

需要 Docker 与 Docker Compose v2。

```bash
docker compose up --build
```

启动后：

- 服务地址：http://localhost:8080
- Swagger UI：http://localhost:8080/swagger-ui.html
- OpenAPI JSON：http://localhost:8080/v3/api-docs
- 健康检查：http://localhost:8080/actuator/health

Compose 包含 3 个服务：`postgres`、`kafka`（KRaft 单节点）、`app`，app 会等待二者健康后启动。

## 本地开发与验证（无需 Docker / PostgreSQL / Kafka）

JDK 17 + Maven 3.8+，使用 `local` profile（H2 内存库、Kafka 关闭、设备事件同步处理）：

```bash
# 运行全部测试（10 个联动场景 + 5 个异味反复整改场景 + 1 个内嵌 Kafka 冒烟）
mvn test

# 本地启动（H2，自动写入种子数据）
mvn spring-boot:run -Dspring-boot.run.profiles=local

# 或打包后运行
mvn package -DskipTests
java -jar target/garbage-room-service.jar --spring.profiles.active=local
```

一键演示脚本（自动构建、启动、调用全链路、停止）：

```bash
scripts/verify.sh
```

## 测试账号 / 种子数据

| 角色 | 标识 | 说明 |
| --- | --- | --- |
| 居民 | 卡号 `CARD1001` | 王建国，阳光花园 12栋1单元，初始 100 积分 |
| 居民 | 卡号 `CARD1002` | 李秀兰，阳光花园 12栋2单元，初始 100 积分 |
| 居民 | 卡号 `CARD1003` | 张伟，翠柳小区 3栋1单元，初始 100 积分 |
| 物业 | 无需登录（演示用） | `GET /api/property/tasks/pending` 看待处理任务 |
| 环卫 | 无需登录（演示用） | `GET /api/sanitation/truck-priorities` 看清运优先级 |
| 街道 | 无需登录（演示用） | `GET /api/street/overview` 看分类质量与投诉趋势 |

垃圾房与小区：

| 垃圾房 | 小区 | 街道 |
| --- | --- | --- |
| `ROOM1` 阳光花园1号垃圾房 | `C001` 阳光花园 | 滨江街道 |
| `ROOM2` 阳光花园2号垃圾房 | `C001` 阳光花园 | 滨江街道 |
| `ROOM3` 翠柳小区1号垃圾房 | `C002` 翠柳小区 | 滨江街道 |

## 联动场景与验证方式

| 场景 | 触发方式 | 联动动作 | 自动化测试 |
| --- | --- | --- | --- |
| 厨余桶满但清运车未到 | 上报 `KITCHEN_FULL` ≥90% 且车未到场 | 环卫最高优先级（100）清运任务；车到场自动销警 | `kitchenFullNoTruck_...` |
| 居民错投被识别 | 投放时摄像头 `detectedType` ≠ 投放桶 | 记录违规提示、扣分、派现场复核任务 | `odorAndMissort_...` |
| 摄像头误判申诉 | 居民申诉 + 物业复核成立 | 撤销违规、退还积分、错投告警忽略销单 | 同上 |
| 异味超标/异味投诉 | `ODOR` 上报或居民投诉 | 派消杀+保洁任务；投诉并入同一联动单，整改反馈后闭环 | `odorComplaint_...` |
| 夜间投放高峰 | 20:00–次日06:00 投放 ≥3 次 | 增派夜间保洁值守任务 | `nightPeak_...` |
| 设备断网 | 心跳超时（默认 10 分钟） | 派维修任务；恢复心跳自动销警 | `offline_heartbeatRecovered` |
| 清运后未清洁 | 清运车离场后 15 分钟未清洁确认 | 补派保洁；环卫清洁确认后销警 | `notCleanedAfterCollection_...` |
| 积分未到账 | 断网 `deferPoints=true` 暂挂超时 | 产生整改任务，物业核查补发积分与流水 | `pointsUnsettled_reconciled` |
| 多告警合并 | 满溢+异味+错投同时出现 | **只形成一张联动单**，任务同单去重，全部处置后销单 | `odorAndMissort_...` / `allRolesShareSameRoomStateAndCase` |
| 异味反复整改 | 一周内异味超标 ≥3 次 | 生成整改单：消杀+桶体清洗+排风设备检查+清运频次复盘 | `recurringOdorTriggersRectificationWithFourTasks` |
| 整改反馈与观察 | 物业上传照片+设备读数，登记责任人/复查时间 | 整改单进入观察期，街道同步责任人/复查时间，持续看异味曲线、投诉、满溢 | `reportEntersVerifying...` / `streetObservesOdorCurve...` |
| 清洗后仍超标 | 观察期内再次超标，复查到期评估 | 升级下一轮整改，建议排风/桶体密封/清运频次而非重复消杀 | `stillExceedingAfterCleaningEscalates...` |
| 整改有效闭环 | 观察期无超标无投诉 | 复查到期自动闭环“整改有效” | `noRecurrenceClosesImproved` |
| 分类质量/投诉趋势 | 街道端查询 | 按小区正确率、错投量、投诉量、按天趋势 | `streetQualityByCommunity` |
| Kafka 事件链路 | 设备事件发布到 `device-events` | 消费者异步消费后刷新状态并联动 | `KafkaLinkageSmokeTest` |

### 手动调用示例

```bash
# 1) 厨余桶满溢 + 异味（同一垃圾房 -> 合并为一张联动单）
curl -X POST localhost:8080/api/device/events -H 'Content-Type: application/json' \
  -d '{"eventType":"KITCHEN_FULL","roomCode":"ROOM1","binType":"KITCHEN","capacityPct":95}'
curl -X POST localhost:8080/api/device/events -H 'Content-Type: application/json' \
  -d '{"eventType":"ODOR","roomCode":"ROOM1","odorValue":88}'

# 2) 环卫看清运优先级
curl localhost:8080/api/sanitation/truck-priorities

# 3) 居民错投（投厨余桶，摄像头识别为其他垃圾）
curl -X POST localhost:8080/api/resident/disposals -H 'Content-Type: application/json' \
  -d '{"cardNo":"CARD1001","roomCode":"ROOM1","binType":"KITCHEN","detectedType":"OTHER","weightKg":0.8,"confidence":0.93}'

# 4) 物业看待处理任务并完成消杀
curl localhost:8080/api/property/tasks/pending
curl -X POST localhost:8080/api/property/tasks/1/complete -H 'Content-Type: application/json' \
  -d '{"note":"已消杀冲洗"}'

# 5) 清运车到场/离场 + 环卫清洁确认
curl -X POST localhost:8080/api/device/events -H 'Content-Type: application/json' \
  -d '{"eventType":"TRUCK_ARRIVAL","roomCode":"ROOM1","truckPresent":true}'
curl -X POST localhost:8080/api/device/events -H 'Content-Type: application/json' \
  -d '{"eventType":"TRUCK_ARRIVAL","roomCode":"ROOM1","truckPresent":false}'
curl -X POST localhost:8080/api/sanitation/rooms/ROOM1/clean-confirm

# 6) 四端共享的垃圾房联动视图
curl localhost:8080/api/rooms/ROOM1

# 7) 异味反复整改：一周内 3 次超标 -> 整改单（消杀/桶体清洗/排风检查/清运复盘）
for v in 75 80 85; do
  curl -X POST localhost:8080/api/device/events -H 'Content-Type: application/json' \
    -d "{\"eventType\":\"ODOR\",\"roomCode\":\"ROOM3\",\"odorValue\":$v}"
done
curl localhost:8080/api/property/odor-rectifications          # 物业看整改单
curl localhost:8080/api/property/cases/{caseNo}/tasks         # 四类整改任务

# 8) 物业整改后上传照片与设备读数，登记责任人与复查时间
curl -X POST localhost:8080/api/property/odor-rectifications/1/report \
  -H 'Content-Type: application/json' \
  -d '{"assigneeName":"张师傅","odorReading":45,"exhaustReading":12,
       "photoUrls":["http://img.example/rectify1.jpg"],"note":"已消杀、桶体清洗并检修排风"}'

# 9) 街道端观察：责任人/复查时间、异味曲线、整改前后对比、观察期投诉与满溢
curl 'localhost:8080/api/street/odor-rectifications?roomCode=ROOM3'
curl localhost:8080/api/street/odor-rectifications/1
curl 'localhost:8080/api/street/rooms/ROOM3/odor-curve?days=7'
```

## API 分组（Swagger UI 可在线试用）

- **设备端** `POST /api/device/events`：9 类设备事件统一上报（启用 Kafka 时异步，投递失败自动降级同步）
- **居民端** `/api/resident/*`：投放、积分与流水、违规提示、误判申诉、异味投诉
- **物业端** `/api/property/*`：待处理任务、派发/完成、联动单查看、投诉整改反馈、积分核查补发、**异味整改单与整改反馈（照片+设备读数+责任人+复查时间）**
- **环卫端** `/api/sanitation/*`：清运优先级、清运后清洁确认
- **街道端** `/api/street/*`：小区分类质量与投诉按天趋势、**异味整改单（含责任人/复查时间）、整改前后异味曲线对比、垃圾房异味曲线**
- **垃圾房统一状态** `/api/rooms/{code}`：实时状态 + 活跃告警 + 治理任务（四端共用）

## 联动规则（核心实现说明）

1. **告警不直接派单**：`LinkageEngine.raiseAlert` 先按 `roomCode` 找当前活跃联动单（`GarbageRoom.activeCaseNo`），没有才建单；同类型未闭环告警按 `dedupeKey` 去重。
2. **任务由告警类型派生且同单去重**：满溢→环卫清运（100）、其他垃圾满→清运（80）、异味/投诉→消杀（70）+保洁（60）、断网→维修（75）、错投/误判→复核（55）、夜间高峰→夜间保洁（50）、未清洁→补保洁（85）、积分未到账→整改（45）。
3. **闭环销单**：告警解除时其关联任务自动完成；活跃告警与未完成任务全部清零，联动单才闭环并释放垃圾房状态。全误判单记 `CLOSED_FALSE`，存在真实处置的单记 `RESOLVED`。
4. **账实一致**：每次积分变动写 `points_ledger`（含 `disposalId`）；申诉退还、核查补发同样留痕；投诉、整改均回链联动单。
5. **定时巡检**（`LinkageWatchdog`）：断网心跳、清运后未清洁、积分超时未到账三类被动场景自动补告警；**异味整改复查到期自动评估**（观察期内异味超标/投诉/满溢）。
6. **阈值可配**（`app.linkage.*`，见 `application.yml`）：满溢 90%、异味 70、夜间 20:00–06:00/3 次、断网 10 分钟、未清洁 15 分钟、10 分/kg、错投扣 5 分、**异味反复 7 天/3 次、默认复查 48 小时**。
7. **异味反复整改闭环**：每次异味上报记入 `odor_reading` 曲线；一周窗口内超标次数达阈值且当前无进行中整改单时，产生 `ODOR_RECURRING` 告警并开整改单（消杀/桶体清洗/排风检查/清运复盘四类任务挂同一联动单）。物业提交整改反馈（照片+设备读数+责任人+复查时间）后任务自动完成、整改单进入观察期；复查到期评估：无超标无投诉记 `CLOSED_IMPROVED`，否则记 `CLOSED_ESCALATED` 并生成下一轮整改——任务优先级调整为排风检查(90)>清运复盘(85)>桶体清洗(80)>消杀(50)，建议文本明确“避免仅重复消杀”。

## 已执行的验证结论

- `mvn test`：**16 个测试全部通过**（10 个联动全链路场景 + 5 个异味反复整改场景 + 1 个内嵌 Kafka 异步消费冒烟）。
- 本地 `local` profile 实际启动成功，`/actuator/health=UP`，Swagger UI 可访问，OpenAPI 3.0.1 共 30 个路径（含 6 个异味整改新接口）。
- 真实 HTTP 演练确认：满溢+异味+错投合并为同一联动单；错投扣分 100→95，申诉成立退还→100；消杀、清运到场/离场、清洁确认后联动单闭环、物业待办清零；环卫优先级 ROOM1(100) 排首位；投诉整改后状态 RESOLVED。
- `scripts/verify.sh` 实际跑通异味反复整改全链路：ROOM3 一周内 3 次超标生成整改单（任务类型含 DISINFECTION/BIN_DEEP_CLEAN/EXHAUST_CHECK/COLLECTION_REVIEW）；物业反馈照片+读数后整改单 VERIFYING，街道端同步显示责任人“张师傅”与复查时间；观察期异味均值 80.0→41.0（improved=true）；ROOM2 清洗后再报 78 超标，复查到期经看门狗评估为 CLOSED_ESCALATED 并生成第 2 轮整改，建议“检查排风设备运行与风量、桶体密封条老化、清运频次是否不足，避免仅重复消杀”。
- Docker Compose 配置已提供（本工作环境无 Docker 守护进程，未在此机器实际执行镜像构建；容器内构建逻辑与本地 Maven 构建一致）。
