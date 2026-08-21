#!/usr/bin/env bash
# Prompt 管理验证 PM-01~05(生产级, 非售后锚点)
# 前置: model-server + retrieval + evidence + knowledge + eval 5 服务已重启为 Prompt 管理版本
# 用法: bash deploy/prompt-verify.sh
set -uo pipefail

SYSTEM=48081
MODEL=48091
RETRIEVAL=48086
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

pm_create() { # pm_create <key> <name> <content>(用 python 构造 JSON, 防内嵌引号炸 JSON)
  local body
  body=$(python3 -c "import json,sys; print(json.dumps({'promptKey':sys.argv[1],'name':sys.argv[2],'content':sys.argv[3]}, ensure_ascii=False))" "$1" "$2" "$3")
  curl -s -X POST "http://127.0.0.1:${MODEL}/admin-api/model/prompt/create" \
    -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" -H "Content-Type: application/json" \
    -d "$body" | python3 -c "import sys,json; print(json.load(sys.stdin)['data'])"
}
pm_enable() { # pm_enable <id>(enable 端点为 @RequestBody, 需传 body)
  curl -s -X POST "http://127.0.0.1:${MODEL}/admin-api/model/prompt/enable" \
    -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" -H "Content-Type: application/json" \
    -d "{\"id\":$1}" >/dev/null
}
pm_gray() { # pm_gray <id> <tenantIdsJson>
  curl -s -X POST "http://127.0.0.1:${MODEL}/admin-api/model/prompt/gray-enable" \
    -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" -H "Content-Type: application/json" \
    -d "{\"id\":$1,\"tenantIds\":$2}" >/dev/null
}
pm_disable_all() { # 停用某 key 全部行(回退代码默认)
  for id in $(curl -s "http://127.0.0.1:${MODEL}/admin-api/model/prompt/page?pageNo=1&pageSize=50&promptKey=query-disambiguate" \
    -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" | python3 -c "
import sys,json
for r in (json.load(sys.stdin)['data']['list']): print(r['id'])" 2>/dev/null); do
    curl -s -X PUT "http://127.0.0.1:${MODEL}/admin-api/model/prompt/update" \
      -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" -H "Content-Type: application/json" \
      -d "{\"id\":$id,\"status\":0}" >/dev/null 2>&1 || true
  done
}
pm_delete_key() { # 物理删除某 key 全部行(测试清理)
  for id in $(curl -s "http://127.0.0.1:${MODEL}/admin-api/model/prompt/page?pageNo=1&pageSize=50&promptKey=$1" \
    -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" | python3 -c "
import sys,json
for r in (json.load(sys.stdin)['data']['list']): print(r['id'])" 2>/dev/null); do
    curl -s -X DELETE "http://127.0.0.1:${MODEL}/admin-api/model/prompt/delete?id=$id" \
      -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" >/dev/null 2>&1 || true
  done
}

retrieve_rewrites() { # retrieve_rewrites <tenant> <token> → analysis.rewrites(可观测标记, 不受意图钳制)
  curl -s -m 120 -X POST "http://127.0.0.1:${RETRIEVAL}/admin-api/retrieval/search" \
    -H "tenant-id: $1" -H "Authorization: Bearer $2" -H "Content-Type: application/json" \
    -d '{"query":"碎屏能免费修吗","topK":3}' | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
print(json.dumps(d.get('analysis',{}).get('rewrites') or [], ensure_ascii=False))" 2>/dev/null
}

echo ""
echo "========== PM-01 生效: 建 query-disambiguate DB prompt(改写标记 DB改写TEST) =========="
V1=$(pm_create query-disambiguate "测试版1" '你是测试用查询分析器。只输出合法 JSON: {"intent":"保修","entities":[],"products":[],"rewrites":["DB改写TEST"],"sub_questions":[]}')
pm_enable "$V1"
sleep 32 # 缓存 30s
echo "rewrites(应含 DB改写TEST): $(retrieve_rewrites $TENANT1 $T1)"

echo ""
echo "========== PM-02 版本切换/回滚: 建 v2(OTHER) → 启用 → 回滚 v1 =========="
V2=$(pm_create query-disambiguate "测试版2" '你是测试用查询分析器。只输出合法 JSON: {"intent":"保修","entities":[],"products":[],"rewrites":["DB改写OTHER"],"sub_questions":[]}')
pm_enable "$V2"; sleep 32
echo "rewrites(v2 生效, 应含 DB改写OTHER): $(retrieve_rewrites $TENANT1 $T1)"
pm_enable "$V1"; sleep 32
echo "rewrites(回滚 v1, 应含 DB改写TEST): $(retrieve_rewrites $TENANT1 $T1)"

echo ""
echo "========== PM-03 租户灰度: v3 全量(BASE_INTENT) + v4 灰度租户2(GRAY_INTENT) =========="
V3=$(pm_create query-disambiguate "全量版" '你是测试用查询分析器。只输出合法 JSON: {"intent":"保修","entities":[],"products":[],"rewrites":["DB改写BASE"],"sub_questions":[]}')
V4=$(pm_create query-disambiguate "灰度版" '你是测试用查询分析器。只输出合法 JSON: {"intent":"保修","entities":[],"products":[],"rewrites":["DB改写GRAY"],"sub_questions":[]}')
pm_enable "$V3"; pm_gray "$V4" "[122]"; sleep 32
gpr() { # gpr <tenant> <token> → get-prompt 内容片段
  curl -s -m 10 "http://127.0.0.1:${MODEL}/admin-api/model/prompt/get-prompt?key=query-disambiguate&tenantId=$1" \
    -H "tenant-id: $1" -H "Authorization: Bearer $2" | python3 -c "import sys,json; d=json.load(sys.stdin); print((d.get('data') or '')[:60])" 2>/dev/null
}
echo "租户1 get-prompt(应 BASE_INTENT 内容): $(gpr $TENANT1 $T1)"
echo "租户122 get-prompt(应 GRAY_INTENT 内容): $(gpr $TENANT2 $T2)"

echo ""
echo "========== PM-04 降级: 删除全部 query-disambiguate 行 → 回退代码默认 =========="
pm_delete_key query-disambiguate; sleep 32
echo "rewrites(应回退代码默认, 非 DB改写): $(retrieve_rewrites $TENANT1 $T1)"

echo ""
echo "========== PM-05 通用链路(非售后): 公司文档库问答仍通 =========="
EVIDENCE=48087
curl -s -m 180 -X POST "http://127.0.0.1:${EVIDENCE}/admin-api/evidence/evaluate" \
  -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" -H "Content-Type: application/json" \
  -d '{"query":"忘记密码怎么办","kbIds":[3],"topK":8}' | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
print('answerable:', d.get('answerable'), '| answer:', (d.get('answer') or '∅')[:50])
"

echo ""
echo "========== 清理: 确认 query-disambiguate 无残留 =========="
curl -s "http://127.0.0.1:${MODEL}/admin-api/model/prompt/page?pageNo=1&pageSize=10&promptKey=query-disambiguate" \
  -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" | python3 -c "
import sys,json
print('残留行数:', (json.load(sys.stdin)['data']['total']))"
