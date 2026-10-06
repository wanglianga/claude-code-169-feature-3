#!/usr/bin/env bash
# 一键验证脚本：本地构建 -> 以 local profile(H2/无Kafka) 启动 -> 演练联动全链路 -> 停止
set -euo pipefail

cd "$(dirname "$0")/.."
PORT="${PORT:-8080}"
B="http://localhost:${PORT}"
JAR="target/garbage-room-service.jar"
LOG="/tmp/garbage-room-verify.log"
PID=""

cleanup() {
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

echo "==> [1/4] 打包（跳过测试；完整测试请执行 mvn test）"
mvn -q -B package -DskipTests

echo "==> [2/4] 启动应用（local profile，日志：$LOG）"
# 看门狗间隔调小，便于演示“异味整改复查到期评估”；生产默认 30s
java -jar "$JAR" --spring.profiles.active=local --server.port="$PORT" \
  --app.watchdog.fixed-delay-ms=2000 >"$LOG" 2>&1 &
PID=$!

for i in $(seq 1 60); do
  if curl -sf "$B/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -sf "$B/actuator/health" >/dev/null
echo "    health: $(curl -s "$B/actuator/health")"

post() { curl -s -X POST "$B$1" -H 'Content-Type: application/json' -d "$2"; }

echo "==> [3/4] 联动场景演练"
post /api/device/events '{"eventType":"KITCHEN_FULL","roomCode":"ROOM1","binType":"KITCHEN","capacityPct":95}' >/dev/null
post /api/device/events '{"eventType":"ODOR","roomCode":"ROOM1","odorValue":88}' >/dev/null
echo "    环卫优先级首位: $(curl -s "$B/api/sanitation/truck-priorities" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d[0]["roomCode"],d[0]["priorityScore"],d[0]["reason"])')"

MISSORT=$(post /api/resident/disposals '{"cardNo":"CARD1001","roomCode":"ROOM1","binType":"KITCHEN","detectedType":"OTHER","weightKg":0.8,"confidence":0.93}')
DID=$(echo "$MISSORT" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
echo "    错投扣分后积分: $(curl -s "$B/api/resident/CARD1001/profile" | python3 -c 'import sys,json;print(json.load(sys.stdin)["totalPoints"])')（初始100，应=95）"

post "/api/resident/disposals/$DID/appeal" '{"reason":"投放物为剩菜"}' >/dev/null
post "/api/resident/disposals/$DID/appeal-review" '{"accepted":true,"reviewNote":"现场复核为厨余，摄像头误判"}' >/dev/null
echo "    申诉成立退还后积分: $(curl -s "$B/api/resident/CARD1001/profile" | python3 -c 'import sys,json;print(json.load(sys.stdin)["totalPoints"])')（应=100）"

echo "    合并联动单: $(curl -s "$B/api/rooms/ROOM1" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d["room"]["activeCaseNo"],[a["type"] for a in d["activeAlerts"]])')"

DTASK=$(curl -s "$B/api/property/tasks/pending" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(next(t["id"] for t in d if t["type"]=="DISINFECTION"))')
post "/api/property/tasks/$DTASK/complete" '{"note":"已消杀冲洗"}' >/dev/null
post /api/device/events '{"eventType":"TRUCK_ARRIVAL","roomCode":"ROOM1","truckPresent":true}' >/dev/null
post /api/device/events '{"eventType":"TRUCK_ARRIVAL","roomCode":"ROOM1","truckPresent":false}' >/dev/null
curl -s -X POST "$B/api/sanitation/rooms/ROOM1/clean-confirm" >/dev/null
ACTIVE=$(curl -s "$B/api/rooms/ROOM1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["room"]["activeCaseNo"])')
PENDING=$(curl -s "$B/api/property/tasks/pending" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)))')
echo "    全闭环后活跃联动单: ${ACTIVE:-无} ; 物业待办数: $PENDING"

CID=$(post /api/resident/complaints '{"contact":"13800000000","roomCode":"ROOM2","type":"ODOR","content":"2号房异味"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
post "/api/property/complaints/$CID/reply" '{"reply":"已安排保洁消杀"}' >/dev/null
echo "    街道质量视图: $(curl -s "$B/api/street/overview?days=7" | python3 -c 'import sys,json;d=json.load(sys.stdin);print([(x["communityName"],x["correctRatePct"],x["complaintCount"]) for x in d])')"

echo "==> [4/4] 结果判定"
if [ "$ACTIVE" = "None" ] && [ "$PENDING" = "0" ]; then
  echo "PASS: 告警合并 -> 多角色任务 -> 积分退还 -> 清运清洁 -> 联动单闭环 全部验证通过"
else
  echo "FAIL: activeCaseNo=$ACTIVE pendingTasks=$PENDING"; exit 1
fi

echo "==> [附加] 异味反复整改演练（ROOM3 整改观察 / ROOM2 清洗后仍超标升级）"
# ROOM3：一周内 3 次异味超标 -> 生成整改单（消杀/桶体清洗/排风设备检查/清运频次复盘）
for v in 75 80 85; do
  post /api/device/events "{\"eventType\":\"ODOR\",\"roomCode\":\"ROOM3\",\"odorValue\":$v}" >/dev/null
done
RID=$(curl -s "$B/api/property/odor-rectifications" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(next(x["id"] for x in d if x["roomCode"]=="ROOM3"))')
CASE3=$(curl -s "$B/api/street/odor-rectifications/$RID" | python3 -c 'import sys,json;print(json.load(sys.stdin)["rectification"]["caseNo"])')
echo "    整改单任务类型: $(curl -s "$B/api/property/cases/$CASE3/tasks" | python3 -c 'import sys,json;print(sorted({t["type"] for t in json.load(sys.stdin)}))')"

# 物业整改后上传照片与设备读数，登记整改责任人与复查时间（同步街道端）
post "/api/property/odor-rectifications/$RID/report" '{"assigneeName":"张师傅","odorReading":45,"exhaustReading":12,"photoUrls":["http://img.example/rectify1.jpg"],"note":"已消杀、桶体深度清洗并检修排风"}' >/dev/null
echo "    街道端同步责任人与复查时间: $(curl -s "$B/api/street/odor-rectifications?roomCode=ROOM3" | python3 -c 'import sys,json;d=json.load(sys.stdin)[0];print(d["assigneeName"],d["status"],d["reviewAt"])')"

# 观察期异味回落，街道端查看异味曲线与整改前后对比
post /api/device/events '{"eventType":"ODOR","roomCode":"ROOM3","odorValue":40}' >/dev/null
post /api/device/events '{"eventType":"ODOR","roomCode":"ROOM3","odorValue":38}' >/dev/null
echo "    整改前后异味均值: $(curl -s "$B/api/street/odor-rectifications/$RID" | python3 -c 'import sys,json;t=json.load(sys.stdin)["trend"];print(t["avgBeforeReport"],"->",t["avgAfterReport"],"improved=",t["improved"])')"

# ROOM2：清洗后仍超标 -> 复查到期升级，建议排风/桶体密封/清运频次而非重复消杀
for v in 76 81 86; do
  post /api/device/events "{\"eventType\":\"ODOR\",\"roomCode\":\"ROOM2\",\"odorValue\":$v}" >/dev/null
done
RID2=$(curl -s "$B/api/property/odor-rectifications" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(next(x["id"] for x in d if x["roomCode"]=="ROOM2"))')
REVIEW_AT=$(date -u -d '+3 seconds' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v+3S +%Y-%m-%dT%H:%M:%SZ)
post "/api/property/odor-rectifications/$RID2/report" "{\"assigneeName\":\"李师傅\",\"odorReading\":46,\"photoUrls\":[\"http://img.example/rectify2.jpg\"],\"note\":\"已消杀清洗\",\"reviewAt\":\"$REVIEW_AT\"}" >/dev/null
post /api/device/events '{"eventType":"ODOR","roomCode":"ROOM2","odorValue":78}' >/dev/null   # 清洗后仍超标
sleep 6   # 等待复查到期，看门狗评估观察期异味/投诉/满溢
ESC=$(curl -s "$B/api/street/odor-rectifications?roomCode=ROOM2" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d[1]["status"],"| round",d[0]["roundNo"],"|",d[0]["suggestion"])')
echo "    升级结果: $ESC"
if echo "$ESC" | grep -q "CLOSED_ESCALATED" && echo "$ESC" | grep -q "排风"; then
  echo "PASS: 异味反复整改（多次超标开单 -> 照片读数反馈 -> 街道观察 -> 仍超标升级建议）验证通过"
else
  echo "FAIL: 异味整改升级未生效: $ESC"; exit 1
fi
