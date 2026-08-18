#!/usr/bin/env bash
# 证据平台端到端验证 EV-01~EV-06
# 前置: retrieval-server(48086)/evidence-server(48087)/ingestion-server(48085) 均为最新代码构建
# 用法: bash deploy/evidence-verify.sh
set -uo pipefail

GATEWAY=48080
SYSTEM=48081
EVIDENCE=48087
TENANT=1

# 登录拿 token
TOKEN=$(curl -s -m 10 -X POST "http://127.0.0.1:${SYSTEM}/admin-api/system/auth/login" \
  -H "tenant-id: ${TENANT}" -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null)
if [ -z "$TOKEN" ]; then echo "❌ 登录失败"; exit 1; fi
echo "✅ 登录成功"

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
print('consultable:', dd.get('consultable'))
print('refusal    :', dd.get('refusalReason'))
print('evidence   :', len(dd.get('evidence') or []), '条')
print('conflicts  :', len(dd.get('conflicts') or []))
if dd.get('conflicts'):
    for c in dd['conflicts']:
        print('  冲突 #%s ↔ #%s: %s' % (c['evidenceIndexA'], c['evidenceIndexB'], c['reason']))
print('claims     :', len(dd.get('claims') or []), '条')
if dd.get('claims'):
    for c in dd['claims']:
        mark = '✓' if c['verdict'] == 'SUPPORTED' else '✗'
        print('  %s [%s] %s' % (mark, c['verdict'], (c['text'] or '')[:60]))
print('claimFail  :', dd.get('claimFail'))
print('answer     :', (dd.get('answer') or '').replace(chr(10), ' ')[:150] or '∅')
print('elapsedMs  :', dd.get('elapsedMs'))
"
}

ev "EV-01 充分作答(X100 Pro 碎屏)" "X100 Pro 屏幕碎了能免费修吗" null
ev "EV-02 产品不匹配(苹果13 库内无)" "苹果13 碎屏保修多久" null
ev "EV-04 无据断言拦截(诱导问题)" "手机进水了怎么办" null

echo ""
echo "========== EV-05 落库留痕 =========="
docker exec yudao-mysql mysql -uroot -p123456 --default-character-set=utf8mb4 ruoyi-vue-pro \
  -e "SELECT id, trace_id, answerable, confidence, evidence_count, conflict_count, claim_pass FROM ai_evidence_eval ORDER BY id DESC LIMIT 5;" 2>/dev/null
docker exec yudao-mysql mysql -uroot -p123456 --default-character-set=utf8mb4 ruoyi-vue-pro \
  -e "SELECT id, chunk_id, confidence, verdict, trace_id FROM ai_evidence ORDER BY id DESC LIMIT 8;" 2>/dev/null

echo ""
echo "========== EV-06 阈值配置生效(临时改 answer-threshold=0.6 重启 evidence-server 后复测 EV-01 应放行) =========="
echo "提示: 修改 application-local.yaml yudao.evidence.sufficiency.answer-threshold: 0.6, 重启 evidence-server 后重跑本脚本对比 EV-01"

echo ""
echo "========== EV-03 冲突证据(需构造: 同库 V1免费修 vs V2不免费, 见设计文档 §10) =========="
echo "提示: 需先在知识库录入冲突文档版本并发布, 再重跑本脚本"
