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
java -jar "$JAR" --spring.profiles.active=local --server.port="$PORT" >"$LOG" 2>&1 &
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

echo "==> [3.5] 异味反复整改演练（ROOM3）"
# 完成当前全部物业待办的辅助函数（消杀完成会联动销警并自动完成关联任务）
complete_all_pending() {
  for _ in 1 2 3 4 5; do
    IDS=$(curl -s "$B/api/property/tasks/pending" | python3 -c 'import sys,json;print(" ".join(str(t["id"]) for t in json.load(sys.stdin)))')
    [ -z "$IDS" ] && break
    for TID in $IDS; do post "/api/property/tasks/$TID/complete" '{"note":"已处理"}' >/dev/null; done
  done
}
# 1) 一周内 3 次异味超标 -> 自动开整改单（消杀/桶体清洗/排风检查/清运复盘）
post /api/device/events '{"eventType":"ODOR","roomCode":"ROOM3","odorValue":75}' >/dev/null
post /api/device/events '{"eventType":"ODOR","roomCode":"ROOM3","odorValue":80}' >/dev/null
post /api/device/events '{"eventType":"ODOR","roomCode":"ROOM3","odorValue":85}' >/dev/null
RECT=$(curl -s "$B/api/property/odor-rectifications?roomCode=ROOM3" | python3 -c 'import sys,json;print(json.load(sys.stdin)[0]["rectNo"])')
echo "    整改单: $RECT 任务: $(curl -s "$B/api/property/odor-rectifications/$RECT" | python3 -c 'import sys,json;print([t["type"] for t in json.load(sys.stdin)["tasks"]])')"
# 2) 物业完成整改任务并上传照片+设备读数 -> 观察期，责任人与复查时间同步街道端
complete_all_pending
post "/api/property/odor-rectifications/$RECT/complete" '{"ownerName":"陈保洁","photos":["wash1.jpg","vent1.jpg"],"deviceReading":45,"note":"已消杀、清洗桶体并检修排风"}' >/dev/null
echo "    街道端可见: $(curl -s "$B/api/street/odor-rectifications?roomCode=ROOM3" | python3 -c 'import sys,json;d=json.load(sys.stdin)[0];print(d["status"],"责任人",d["ownerName"],"复查时间",d["reviewAt"])')"
# 3) 观察期再超标 -> 升级深度排查（建议排风/桶体密封/清运频次，而非重复消杀）
post /api/device/events '{"eventType":"ODOR","roomCode":"ROOM3","odorValue":82}' >/dev/null
echo "    升级建议: $(curl -s "$B/api/street/odor-rectifications?roomCode=ROOM3" | python3 -c 'import sys,json;print(json.load(sys.stdin)[0]["suggestion"])')"
# 4) 深度整改后复查时间到期 -> 评估闭环
complete_all_pending
NOW=$(python3 -c 'from datetime import datetime,timezone,timedelta;print(datetime.now(timezone(timedelta(hours=8))).isoformat(timespec="seconds"))')
post "/api/property/odor-rectifications/$RECT/complete" "{\"ownerName\":\"陈保洁\",\"photos\":[\"seal1.jpg\"],\"deviceReading\":40,\"reviewAt\":\"$NOW\",\"note\":\"已更换密封条并上调清运班次\"}" >/dev/null
post /api/property/odor-rectifications/evaluate-observations '{}' >/dev/null
RECT_STATUS=$(curl -s "$B/api/street/odor-rectifications?roomCode=ROOM3" | python3 -c 'import sys,json;print(json.load(sys.stdin)[0]["status"])')
echo "    复查到期评估后整改单状态: $RECT_STATUS（应为 CLOSED_EFFECTIVE）"
echo "    异味曲线: $(curl -s "$B/api/street/rooms/ROOM3/odor-curve?days=7" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("读数",[r["value"] for r in d["readings"]],"阈值",d["odorThreshold"])')"

echo "==> [4/4] 结果判定"
if [ "$ACTIVE" = "None" ] && [ "$PENDING" = "0" ] && [ "$RECT_STATUS" = "CLOSED_EFFECTIVE" ]; then
  echo "PASS: 告警合并 -> 多角色任务 -> 积分退还 -> 清运清洁 -> 联动单闭环 -> 异味反复整改闭环 全部验证通过"
else
  echo "FAIL: activeCaseNo=$ACTIVE pendingTasks=$PENDING rectStatus=$RECT_STATUS"; exit 1
fi
