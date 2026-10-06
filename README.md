# 城市智能垃圾房满溢异味投放违规联动服务

面向物业、环卫、街道、居民四类角色的垃圾房现场治理服务。垃圾房设备上报**厨余桶满溢、其他垃圾容量、异味、门禁、摄像头识别、称重、消杀、清运车到场、心跳**数据；居民刷卡/扫码投放时记录**小区、楼栋、垃圾类型、重量、积分与违规提示**。

核心设计目标：**让设备告警真正变成现场治理动作**。满溢、异味、错投往往同时出现，系统不会把每个告警单独派单，而是把同一垃圾房的未闭环告警**合并成一张“治理联动单”**，在单上派生保洁/消杀/清运/维修/复核/整改任务；积分、投诉、整改结果都与真实投放、清运记录一一对应，四端围绕同一份垃圾房状态更新。

针对**异味反复超标**这一顽固场景，系统另有“异味反复整改单”：一周内多次异味超标自动开单，派生**消杀、桶体清洗、排风设备检查、清运频次复盘**四类任务；物业完成整改后上传照片与设备读数并登记责任人，整改单进入观察期，**责任人与复查时间同步街道端**；观察期内服务持续观察异味曲线、投诉与满溢次数，若清洗后仍超标则升级深度排查——**建议检查排风、桶体密封或清运频次，而不是简单重复消杀**；复查到期无超标才闭环。

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
# 运行全部测试（15 个联动场景 + 1 个内嵌 Kafka 冒烟）
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
| 异味反复整改 | 一周内异味超标 ≥3 次（可配） | 开整改单：消杀+桶体清洗+排风检查+清运复盘；物业上传照片/读数后进入观察期，责任人与复查时间同步街道端 | `repeatedOdorExceedance_...` / `completeRectification_...` |
| 清洗后仍超标 | 观察期内异味再次超标或整改后读数仍超标 | 升级深度排查：建议检查排风、桶体密封、清运频次（不再重复消杀），改派排风复查/桶体密封/清运复盘任务 | `exceedanceDuringObservation_...` / `readingStillExceeded_...` |
| 整改效果观察 | 整改完成进入观察期 | 持续观察异味曲线、投诉、满溢次数；复查到期无超标闭环，街道端看整改前后异味均值对比 | `deepRectification_...` |
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

# 7) 异味反复整改：一周内 3 次超标自动开单（ROOM3）
for v in 75 80 85; do
  curl -X POST localhost:8080/api/device/events -H 'Content-Type: application/json' \
    -d "{\"eventType\":\"ODOR\",\"roomCode\":\"ROOM3\",\"odorValue\":$v}"; echo
done
curl 'localhost:8080/api/property/odor-rectifications?roomCode=ROOM3'   # 整改单+4类任务

# 8) 物业完成整改任务后，上传照片与设备读数、登记责任人 -> 观察期
curl localhost:8080/api/property/tasks/pending   # 先完成消杀/桶体清洗/排风检查/清运复盘任务
curl -X POST localhost:8080/api/property/odor-rectifications/{rectNo}/complete \
  -H 'Content-Type: application/json' \
  -d '{"ownerName":"陈保洁","photos":["wash1.jpg","vent1.jpg"],"deviceReading":45,"note":"已消杀清洗并检修排风"}'

# 9) 街道端观察：责任人/复查时间、异味曲线、整改前后均值对比
curl 'localhost:8080/api/street/odor-rectifications?roomCode=ROOM3'
curl 'localhost:8080/api/street/rooms/ROOM3/odor-curve?days=7'
curl localhost:8080/api/street/odor-rectifications/{rectNo}/observation

# 10) 观察期再超标 -> 自动升级深度排查（建议排风/桶体密封/清运频次，而非重复消杀）
curl -X POST localhost:8080/api/device/events -H 'Content-Type: application/json' \
  -d '{"eventType":"ODOR","roomCode":"ROOM3","odorValue":82}'
```

## API 分组（Swagger UI 可在线试用）

- **设备端** `POST /api/device/events`：9 类设备事件统一上报（启用 Kafka 时异步，投递失败自动降级同步）
- **居民端** `/api/resident/*`：投放、积分与流水、违规提示、误判申诉、异味投诉
- **物业端** `/api/property/*`：待处理任务、派发/完成、联动单查看、投诉整改反馈、积分核查补发、**异味整改单（查询/完成整改上传照片读数/观察期评估）**
- **环卫端** `/api/sanitation/*`：清运优先级、清运后清洁确认
- **街道端** `/api/street/*`：小区分类质量（正确率/错投/重量）与投诉按天趋势、全街道对比、**异味整改单（责任人+复查时间）、异味曲线、整改前后效果对比**
- **垃圾房统一状态** `/api/rooms/{code}`：实时状态 + 活跃告警 + 治理任务（四端共用）

## 联动规则（核心实现说明）

1. **告警不直接派单**：`LinkageEngine.raiseAlert` 先按 `roomCode` 找当前活跃联动单（`GarbageRoom.activeCaseNo`），没有才建单；同类型未闭环告警按 `dedupeKey` 去重。
2. **任务由告警类型派生且同单去重**：满溢→环卫清运（100）、其他垃圾满→清运（80）、异味/投诉→消杀（70）+保洁（60）、断网→维修（75）、错投/误判→复核（55）、夜间高峰→夜间保洁（50）、未清洁→补保洁（85）、积分未到账→整改（45）。
3. **闭环销单**：告警解除时其关联任务自动完成；活跃告警与未完成任务全部清零，联动单才闭环并释放垃圾房状态。全误判单记 `CLOSED_FALSE`，存在真实处置的单记 `RESOLVED`。
4. **账实一致**：每次积分变动写 `points_ledger`（含 `disposalId`）；申诉退还、核查补发同样留痕；投诉、整改均回链联动单。
5. **定时巡检**（`LinkageWatchdog`）：断网心跳、清运后未清洁、积分超时未到账三类被动场景自动补告警；异味整改观察期到期自动评估闭环或升级。
6. **异味反复整改**（`OdorRectificationService`）：每次异味上报写入 `odor_reading` 构成曲线；窗口内（默认 7 天）超标次数达阈值（默认 3 次）自动开整改单，派生消杀/桶体清洗/排风检查/清运复盘四任务；物业完成任务后须上传照片+设备读数并登记责任人才可提交，整改单进入观察期（默认 3 天，复查时间=完成时间+观察期，可约定）；观察期内再超标或整改后读数仍超标即升级，系统建议检查排风、桶体密封或清运频次并改派对应任务，**不再简单重复消杀**；复查到期无超标记 `CLOSED_EFFECTIVE` 闭环。整改状态变化广播到 `governance-events`，责任人与复查时间同步街道端。
7. **阈值可配**（`app.linkage.*`，见 `application.yml`）：满溢 90%、异味 70、夜间 20:00–06:00/3 次、断网 10 分钟、未清洁 15 分钟、10 分/kg、错投扣 5 分、异味反复 7 天/3 次、整改观察期 3 天。

## 已执行的验证结论

- `mvn test`：**16 个测试全部通过**（10 个联动全链路场景 + 5 个异味反复整改场景 + 1 个内嵌 Kafka 异步消费冒烟）。
- 本地 `local` profile 实际启动成功，`/actuator/health=UP`，Swagger UI 返回 200，OpenAPI 3.0.1 共 31 个路径（含 7 个异味整改相关新端点）。
- 真实 HTTP 演练确认：满溢+异味+错投合并为同一联动单；错投扣分 100→95，申诉成立退还→100；消杀、清运到场/离场、清洁确认后联动单闭环、物业待办清零；环卫优先级 ROOM1(100) 排首位；投诉整改后状态 RESOLVED。
- `scripts/verify.sh` 实际执行通过，异味反复整改全链路实测结论：
  - ROOM3 一周内 3 次异味超标（75/80/85）→ 自动开整改单 `ODR-ROOM3-*`，生成消杀、桶体清洗、排风设备检查、清运频次复盘 4 项任务；
  - 物业完成任务并上传照片（wash1.jpg/vent1.jpg）+ 设备读数 45 + 责任人“陈保洁”→ 整改单进入 OBSERVING，街道端同步看到责任人与复查时间（完成时间+3 天）；
  - 观察期再次超标（82）→ 自动升级 ESCALATED，建议“检查排风设备运行、桶体密封性或上调清运频次，而非简单重复消杀”，并改派排风复查/桶体密封/清运复盘任务（无重复消杀任务）；
  - 深度整改后复查到期评估 → `CLOSED_EFFECTIVE`；街道端异味曲线读数序列 [75, 80, 85, 45, 82, 40]（阈值 70），整改前后均值对比可见异味下降。
- Docker Compose 配置已提供（本工作环境无 Docker 守护进程，未在此机器实际执行镜像构建；容器内构建逻辑与本地 Maven 构建一致）。
