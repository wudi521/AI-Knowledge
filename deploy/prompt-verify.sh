#!/usr/bin/env bash
# Prompt 管理验证 PM-01~05(生产级, 非售后锚点; 确定性验证: 源端 get-prompt + 端到端钳制信号)
# 前置: model-server + retrieval + evidence + knowledge + eval 5 服务均为最新代码
# 用法: bash deploy/prompt-verify.sh
set -uo pipefail

SYSTEM=48081
MODEL=48091
RETRIEVAL=48086
EVIDENCE=48087
TENANT1=1
TENANT2=122

tok() { # tok <tenant> <username>
  curl -s -m 10 -X POST "http://127.0.0.1:${SYSTEM}/admin-api/system/auth/login" \
    -H "tenant-id: $1" -H "Content-Type: application/json" \
    -d "{\"username\":\"$2\",\"password\":\"admin123\"}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null
}
T1=$(tok $TENANT1 admin); T2=$(tok $TENANT2 aoteman)
if [ -z "$T1" ] || [ -z "$T2" ]; then echo "❌ 登录失败"; exit 1; fi
echo "✅ 双租户登录成功"

pm_create() { # pm_create <key> <name> <content>(python 构造 JSON, 防内嵌引号)
  local body
  body=$(python3 -c "import json,sys; print(json.dumps({'promptKey':sys.argv[1],'name':sys.argv[2],'content':sys.argv[3]}, ensure_ascii=False))" "$1" "$2" "$3")
  curl -s -X POST "http://127.0.0.1:${MODEL}/admin-api/model/prompt/create" \
    -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" -H "Content-Type: application/json" \
    -d "$body" | python3 -c "import sys,json; print(json.load(sys.stdin)['data'])"
}
pm_enable() { # pm_enable <id>(@RequestBody)
  curl -s -X POST "http://127.0.0.1:${MODEL}/admin-api/model/prompt/enable" \
    -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" -H "Content-Type: application/json" \
    -d "{\"id\":$1}" >/dev/null
}
pm_gray() { # pm_gray <id> <tenantIdsJson>
  curl -s -X POST "http://127.0.0.1:${MODEL}/admin-api/model/prompt/gray-enable" \
    -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" -H "Content-Type: application/json" \
    -d "{\"id\":$1,\"tenantIds\":$2}" >/dev/null
}
pm_delete_key() { # 删除某 key 全部行
  for id in $(curl -s "http://127.0.0.1:${MODEL}/admin-api/model/prompt/page?pageNo=1&pageSize=50&promptKey=$1" \
    -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" | python3 -c "
import sys,json
for r in (json.load(sys.stdin)['data']['list']): print(r['id'])" 2>/dev/null); do
    curl -s -X DELETE "http://127.0.0.1:${MODEL}/admin-api/model/prompt/delete?id=$id" \
      -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" >/dev/null 2>&1 || true
  done
}

gpr() { # gpr <tenant> <token> → get-prompt 内容前40字
  curl -s -m 10 "http://127.0.0.1:${MODEL}/admin-api/model/prompt/get-prompt?key=query-disambiguate&tenantId=$1" \
    -H "tenant-id: $1" -H "Authorization: Bearer $2" | python3 -c "import sys,json; print((json.load(sys.stdin).get('data') or '')[:40])" 2>/dev/null
}
retrieve_intent() { # 检索意图(端到端信号)
  curl -s -m 120 -X POST "http://127.0.0.1:${RETRIEVAL}/admin-api/retrieval/search" \
    -H "tenant-id: $1" -H "Authorization: Bearer $2" -H "Content-Type: application/json" \
    -d '{"query":"碎屏能免费修吗","topK":3}' | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
print(d.get('analysis',{}).get('intent','∅'))" 2>/dev/null
}

echo ""
echo "========== PM-01 端到端生效: DB prompt 到达 LLM(强制 TEST_INTENT → 越界钳制 OUT_OF_SCOPE) =========="
V1=$(pm_create query-disambiguate "测试版1" '你是测试用查询分析器。只输出合法 JSON: {"intent":"TEST_INTENT","entities":[],"products":[],"rewrites":[],"sub_questions":[]}')
echo "v1 id=$V1"; pm_enable "$V1"; sleep 32
echo "intent(应 OUT_OF_SCOPE=DB生效被钳制; 默认应为 保修): $(retrieve_intent $TENANT1 $T1)"

echo ""
echo "========== PM-02 版本切换/回滚(源端确定性) =========="
V2=$(pm_create query-disambiguate "测试版2" '你是测试用查询分析器。只输出合法 JSON: {"intent":"OTHER_INTENT","entities":[],"products":[],"rewrites":[],"sub_questions":[]}')
echo "v2 id=$V2"
echo "启用 v1 内容(应 TEST_INTENT): $(gpr $TENANT1 $T1)"
pm_enable "$V2"; sleep 32
echo "启用 v2 内容(应 OTHER_INTENT): $(gpr $TENANT1 $T1)"
pm_enable "$V1"; sleep 32
echo "回滚 v1 内容(应 TEST_INTENT): $(gpr $TENANT1 $T1)"

echo ""
echo "========== PM-03 租户灰度: v3 全量 + v4 灰度租户122 =========="
V3=$(pm_create query-disambiguate "全量版" '你是测试用查询分析器。只输出合法 JSON: {"intent":"BASE_INTENT","entities":[],"products":[],"rewrites":[],"sub_questions":[]}')
V4=$(pm_create query-disambiguate "灰度版" '你是测试用查询分析器。只输出合法 JSON: {"intent":"GRAY_INTENT","entities":[],"products":[],"rewrites":[],"sub_questions":[]}')
pm_enable "$V3"; pm_gray "$V4" "[122]"; sleep 32
echo "租户1 get-prompt(应 BASE_INTENT): $(gpr $TENANT1 $T1)"
echo "租户122 get-prompt(应 GRAY_INTENT): $(gpr $TENANT2 $T2)"

echo ""
echo "========== PM-04 降级: 删除全部配置 → 回退代码默认 =========="
pm_delete_key query-disambiguate; sleep 32
echo "get-prompt(应空): $(gpr $TENANT1 $T1)"
echo "intent(应回默认 保修): $(retrieve_intent $TENANT1 $T1)"

echo ""
echo "========== PM-05 通用链路(非售后): 公司文档库问答仍通 =========="
curl -s -m 180 -X POST "http://127.0.0.1:${EVIDENCE}/admin-api/evidence/evaluate" \
  -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" -H "Content-Type: application/json" \
  -d '{"query":"忘记密码怎么办","kbIds":[3],"topK":8}' | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
print('answerable:', d.get('answerable'), '| answer:', (d.get('answer') or '∅')[:50])
"

echo ""
echo "========== 清理确认 =========="
curl -s "http://127.0.0.1:${MODEL}/admin-api/model/prompt/page?pageNo=1&pageSize=10&promptKey=query-disambiguate" \
  -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" | python3 -c "
import sys,json
print('残留行数:', (json.load(sys.stdin)['data']['total']))" 2>/dev/null
