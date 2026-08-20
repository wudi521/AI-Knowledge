#!/usr/bin/env bash
# 模型网关多场景验证 MG-01~MG-07(生产级, 非售后锚点)
# 前置: model-server(4809x) 已重启为网关版本; 现网模型 LM Studio 1234 / llama.cpp 1236
# 用法: bash deploy/model-gateway-verify.sh
set -uo pipefail

SYSTEM=48081
MODEL=48091 # model-server 固定端口
TENANT=1

TOKEN=$(curl -s -m 10 -X POST "http://127.0.0.1:${SYSTEM}/admin-api/system/auth/login" \
  -H "tenant-id: ${TENANT}" -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null)
if [ -z "$TOKEN" ]; then echo "❌ 登录失败"; exit 1; fi
echo "✅ 登录成功 (model-server port=$MODEL)"

echo ""
echo "========== MG-00 健康检查: 启用模型可达性 =========="
curl -s -m 10 "http://127.0.0.1:${MODEL}/admin-api/model/health/models" \
  -H "tenant-id: ${TENANT}" -H "Authorization: Bearer ${TOKEN}" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('code:', d['code'])
for m in (d.get('data') or []):
    print(' ', m.get('type'), '|', m.get('modelName'), '| scenario:', m.get('scenario'), '| reachable:', m.get('reachable'))
"

echo ""
echo "========== MG-01 路由 + MG-04 计量: 带场景调用 → 计量表 scenario 归属 =========="
curl -s -m 120 -X POST "http://127.0.0.1:${MODEL}/admin-api/model/chat" \
  -H "tenant-id: ${TENANT}" -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
  -d '{"system":"你是测试助手, 只回OK","user":"hi","scenario":"LOGISTICS","traceId":"mg-01-test"}' \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print('code:', d['code'], '| reply:', (d.get('data') or '∅')[:40])"
docker exec yudao-mysql mysql -uroot -p123456 --default-character-set=utf8mb4 ruoyi-vue-pro \
  -e "SELECT trace_id, scenario, type, model_name, status, attempt, prompt_tokens, completion_tokens, elapsed_ms FROM ai_model_call_log ORDER BY id DESC LIMIT 3;" 2>/dev/null

echo ""
echo "========== MG-02 降级 + MG-03 熔断: 场景 TEST(主不可达+备可达) =========="
# 创建场景 TEST 模型配置(幂等: 已存在则跳过)
for spec in '0|http://127.0.0.1:9/v1|unreachable-chat|TEST' '1|http://127.0.0.1:1234/v1|qwen/qwen3-8b|TEST'; do
  pr=${spec%%|*}; rest=${spec#*|}; url=${rest%%|*}; rest=${rest#*|}; mn=${rest%%|*}; sc=${rest#*|}
  EXIST=$(curl -s "http://127.0.0.1:${MODEL}/admin-api/model/model-config/page?pageNo=1&pageSize=10&type=chat&scenario=${sc}" \
    -H "tenant-id: ${TENANT}" -H "Authorization: Bearer ${TOKEN}" | python3 -c "
import sys,json
rows=(json.load(sys.stdin).get('data') or {}).get('list') or []
print(len(rows))" 2>/dev/null)
  if [ "$EXIST" = "0" ]; then
    curl -s -X POST "http://127.0.0.1:${MODEL}/admin-api/model/model-config/create" \
      -H "tenant-id: ${TENANT}" -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
      -d "{\"name\":\"test-${pr}\",\"type\":\"chat\",\"provider\":\"OPENAI\",\"modelName\":\"${mn}\",\"baseUrl\":\"${url}\",\"status\":1,\"scenario\":\"${sc}\",\"priority\":${pr}}" >/dev/null
  fi
done
echo "场景 TEST 配置就绪"
echo "--- 连续 6 次调用(触发熔断路径) ---"
for i in 1 2 3 4 5 6; do
  R=$(curl -s -m 120 -X POST "http://127.0.0.1:${MODEL}/admin-api/model/chat" \
    -H "tenant-id: ${TENANT}" -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
    -d "{\"system\":\"你是测试助手, 只回OK\",\"user\":\"hi\",\"scenario\":\"TEST\"}" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['code'], (d.get('data') or '')[:20])")
  echo "  [$i] $R"
done
echo "--- 计量核对(应见 FAILED 主 + DEGRADED 备; 熔断后直接走备) ---"
docker exec yudao-mysql mysql -uroot -p123456 --default-character-set=utf8mb4 ruoyi-vue-pro \
  -e "SELECT scenario, model_name, status, attempt, elapsed_ms FROM ai_model_call_log WHERE scenario='TEST' ORDER BY id DESC LIMIT 10;" 2>/dev/null

echo ""
echo "========== MG-05 兼容: 无场景调用(默认路由) =========="
curl -s -m 120 -X POST "http://127.0.0.1:${MODEL}/admin-api/model/chat" \
  -H "tenant-id: ${TENANT}" -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
  -d '{"system":"你是测试助手, 只回OK","user":"hi"}' \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print('code:', d['code'], '| reply:', (d.get('data') or '∅')[:40])"

echo ""
echo "========== MG-06/07 通用链路(非售后): 公司文档库(FAQ/产品手册)全链路 + 计量 =========="
EVIDENCE=48087
for q in "忘记密码怎么办" "密码策略要求是什么"; do
  curl -s -m 180 -X POST "http://127.0.0.1:${EVIDENCE}/admin-api/evidence/evaluate" \
    -H "tenant-id: ${TENANT}" -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
    -d "{\"query\":\"$q\",\"kbIds\":[3],\"topK\":8}" | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
print('[$q] answerable:', d.get('answerable'), '| answer:', (d.get('answer') or '∅')[:60])
"
done
echo "--- 计量核对(chat 应多条 SUCCESS) ---"
docker exec yudao-mysql mysql -uroot -p123456 --default-character-set=utf8mb4 ruoyi-vue-pro \
  -e "SELECT scenario, type, model_name, status, prompt_tokens, completion_tokens, elapsed_ms FROM ai_model_call_log ORDER BY id DESC LIMIT 5;" 2>/dev/null
