#!/usr/bin/env bash
# 槽位检测端到端验证 EV-07~EV-10
# 前置: knowledge-server(48084)/evidence-server(48087) 已重启为含槽位检测的最新代码(需先在 IDEA 重启)
#       目标知识库(第一个)已配置 brand/faultType/purchaseTime 三个必填槽位(见 Task 3 创建命令)
# 用法: bash deploy/slot-verify.sh
set -uo pipefail

GATEWAY=48080
SYSTEM=48081
KNOWLEDGE=48084
EVIDENCE=48087
TENANT=1

# 登录拿 token
TOKEN=$(curl -s -m 10 -X POST "http://127.0.0.1:${SYSTEM}/admin-api/system/auth/login" \
  -H "tenant-id: ${TENANT}" -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null)
if [ -z "$TOKEN" ]; then echo "❌ 登录失败"; exit 1; fi
echo "✅ 登录成功"

# 目标知识库: 取第一个知识库(售后政策库; 需已按 Task 3 建好 3 个必填槽位)
KB=$(curl -s -m 10 "http://127.0.0.1:${KNOWLEDGE}/admin-api/knowledge/knowledge-base/page?pageNo=1&pageSize=1" \
  -H "tenant-id: ${TENANT}" -H "Authorization: Bearer ${TOKEN}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['list'][0]['id'])" 2>/dev/null)
echo "✅ 目标知识库 kb=$KB (需已配置 brand/faultType/purchaseTime 三个必填槽位)"

ev() { # ev <名称> <query> <kbIds json or null>
  local name="$1" query="$2" kb="$3"
  local body
  if [ "$kb" = "null" ]; then
    body=$(python3 -c "import json; print(json.dumps({'query': '$query', 'topK': 8}))")
  else
    body=$(python3 -c "import json; print(json.dumps({'query': '$query', 'kbIds': $kb, 'topK': 8}))")
  fi
  echo ""
  echo "========== $name =========="
  curl -s -m 300 -X POST "http://127.0.0.1:${EVIDENCE}/admin-api/evidence/evaluate" \
    -H "tenant-id: ${TENANT}" -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
    -d "$body" | python3 -c "
import sys, json
d = json.load(sys.stdin)
dd = d.get('data') or {}
print('code       :', d['code'])
print('traceId    :', dd.get('traceId'))
print('answerable :', dd.get('answerable'))
print('confidence :', dd.get('confidence'))
print('refusal    :', dd.get('refusalReason'))
print('slotKbId   :', dd.get('slotKbId'))
print('missingSlots:', dd.get('missingSlots'))
print('clarify    :', dd.get('clarifyQuestion'))
print('extracted  :', dd.get('extractedSlots'))
print('evidence   :', len(dd.get('evidence') or []), '条')
print('claims     :', len(dd.get('claims') or []), '条')
print('answer     :', (dd.get('answer') or '').replace(chr(10), ' ')[:150] or '∅')
print('elapsedMs  :', dd.get('elapsedMs'))
"
}

ev "EV-07 缺槽位反问(我的手机坏了)" "我的手机坏了,可以免费维修吗?还没有一年" "[$KB]"
ev "EV-08 槽位齐全(苹果13摔碎屏)" "苹果13 摔碎屏了,刚买一个月,能免费修吗" "[$KB]"
ev "EV-09 无关问题放行(你好)" "你好" "[$KB]"

echo ""
echo "========== EV-05-风格 落库核对 =========="
docker exec yudao-mysql mysql -uroot -p123456 --default-character-set=utf8mb4 ruoyi-vue-pro \
  -e "SELECT id, trace_id, answerable, refusal_reason, clarify_question, missing_slots, evidence_count FROM ai_evidence_eval ORDER BY id DESC LIMIT 5;" 2>/dev/null

echo ""
echo "========== EV-10 动态生效验证(手动步骤) =========="
echo "1. 停用 brand 槽位: PUT /knowledge/kb-slot/update {id, status:1} (或 UPDATE ai_knowledge_base_slot SET status=1 WHERE slot_code='brand')"
echo "2. 重跑本脚本 → EV-07 missingSlots 应变 2 项(faultType/purchaseTime)"
echo "3. 恢复 brand(status=0) → 回 3 项; 全程无需改代码/重启"
