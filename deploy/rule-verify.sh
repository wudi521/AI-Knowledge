#!/usr/bin/env bash
# 规则引擎验证 MR-01~06(生产级, 非售后锚点)
# 前置: rule-server(48088) + evidence-server(48087) 已重启为规则引擎版本
# 用法: bash deploy/rule-verify.sh
set -uo pipefail

SYSTEM=48081
RULE=48088
EVIDENCE=48087
TENANT1=1
TENANT2=122

tok() {
  curl -s -m 10 -X POST "http://127.0.0.1:${SYSTEM}/admin-api/system/auth/login" \
    -H "tenant-id: $1" -H "Content-Type: application/json" \
    -d "{\"username\":\"$2\",\"password\":\"admin123\"}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null
}
T1=$(tok $TENANT1 admin); T2=$(tok $TENANT2 aoteman)
if [ -z "$T1" ] || [ -z "$T2" ]; then echo "❌ 登录失败"; exit 1; fi
echo "✅ 双租户登录成功"

rule_create() { # rule_create <key> <name> <drl>
  local body
  body=$(python3 -c "import json,sys; print(json.dumps({'ruleKey':sys.argv[1],'name':sys.argv[2],'drlContent':sys.argv[3]}, ensure_ascii=False))" "$1" "$2" "$3")
  curl -s -X POST "http://127.0.0.1:${RULE}/admin-api/rule/create" \
    -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" -H "Content-Type: application/json" \
    -d "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data'] if d['code']==0 else 'ERR:'+str(d['msg']))"
}
rule_enable() { curl -s -X POST "http://127.0.0.1:${RULE}/admin-api/rule/enable" -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" -H "Content-Type: application/json" -d "{\"id\":$1}" >/dev/null; }
rule_validate() { # rule_validate <id> <factsJson>
  curl -s -X POST "http://127.0.0.1:${RULE}/admin-api/rule/validate" \
    -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" -H "Content-Type: application/json" \
    -d "{\"id\":$1,\"facts\":$2}" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('命中:', json.dumps(d.get('data') or [], ensure_ascii=False))" 2>/dev/null
}
rule_delete_key() {
  for id in $(curl -s "http://127.0.0.1:${RULE}/admin-api/rule/page?pageNo=1&pageSize=50&ruleKey=$1" \
    -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" | python3 -c "
import sys,json
for r in (json.load(sys.stdin)['data']['list']): print(r['id'])" 2>/dev/null); do
    curl -s -X DELETE "http://127.0.0.1:${RULE}/admin-api/rule/delete?id=$id" -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" >/dev/null 2>&1 || true
  done
}

LOGISTICS_DRL='import java.util.Map;
import cn.iocoder.yudao.module.rule.service.rule.RuleResult;
rule "cross-province-delivery"
when
  $f: Map($f["region"] == "跨省")
then
  insert(new RuleResult("delivery-3d", "跨省配送时效 3 天"));
end'

echo ""
echo "========== MR-01 编译校验: 合法 DRL 可保存 / 非法 DRL 报错 =========="
ID=$(rule_create delivery-condition "物流时效规则" "$LOGISTICS_DRL")
echo "合法 DRL 创建(应返回 id): $ID"
rule_create bad-rule "非法规则" "this is not valid drools {{{" 
echo "(应 ERR: DRL 编译失败)"

echo ""
echo "========== MR-02 规则命中(evaluate/validate) =========="
rule_enable "$ID"; sleep 32
echo "facts {region:跨省} → $(rule_validate $ID '{"region":"跨省"}')"
echo "facts {region:省内} → $(rule_validate $ID '{"region":"省内"}')"

echo ""
echo "========== MR-03 evidence 规则优先短路(命中规则不走 LLM) =========="
# 配 default 规则: query 含"跨省" → 直接给结论
DEFAULT_DRL='import java.util.Map;
import cn.iocoder.yudao.module.rule.service.rule.RuleResult;
rule "default-cross-province"
when
  $f: Map($f["query"] contains "跨省")
then
  insert(new RuleResult("delivery-3d", "跨省配送时效 3 天"));
end'
DID=$(rule_create default "默认规则(查询触发)" "$DEFAULT_DRL")
echo "default 规则 id=$DID"; rule_enable "$DID"; sleep 32
curl -s -m 120 -X POST "http://127.0.0.1:${EVIDENCE}/admin-api/evidence/evaluate" \
  -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" -H "Content-Type: application/json" \
  -d '{"query":"跨省配送时效要多久","topK":8}' | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
print('answerable:', d.get('answerable'), '| answer:', (d.get('answer') or '∅')[:50], '| evidence:', len(d.get('evidence') or []), '条')"
echo "(期望: answerable=true, answer=跨省配送时效 3 天, evidence=0——规则短路未走检索)"

echo ""
echo "========== MR-04 租户隔离: 租户122 无规则 → 不走短路 =========="
curl -s -m 120 -X POST "http://127.0.0.1:${EVIDENCE}/admin-api/evidence/evaluate" \
  -H "tenant-id: ${TENANT2}" -H "Authorization: Bearer ${T2}" -H "Content-Type: application/json" \
  -d '{"query":"跨省配送时效要多久","kbIds":[3],"topK":8}' | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
print('answerable:', d.get('answerable'), '| answer:', (d.get('answer') or '∅')[:50])"
echo "(期望: 不走规则短路, 走原管线——租户122 无 default 规则)"

echo ""
echo "========== MR-05 版本/回滚(源端) =========="
echo "default 规则 id=$DID 启用中; 建 v2 再回滚(同 prompt 模式, 快速验证)"
V2=$(rule_create default "默认规则v2" 'import java.util.Map;
import cn.iocoder.yudao.module.rule.service.rule.RuleResult;
rule "default-v2"
when
  $f: Map($f["query"] contains "跨省")
then
  insert(new RuleResult("v2-code", "v2 结论"));
end')
echo "v2 id=$V2"; rule_enable "$V2"; sleep 32
curl -s -m 10 "http://127.0.0.1:${RULE}/admin-api/rule/page?pageNo=1&pageSize=5&ruleKey=default" -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" | python3 -c "
import sys,json
for r in (json.load(sys.stdin)['data']['list']): print('  id', r['id'], 'version', r['version'], 'status', r['status'])" 2>/dev/null
echo "(期望: 两条, v2 status=1 启用, v1 status=0)"
rule_enable "$ID" 2>/dev/null || true

echo ""
echo "========== MR-06 HR 规则(非售后): 年假满1年→5天 =========="
HR_DRL='import java.util.Map;
import cn.iocoder.yudao.module.rule.service.rule.RuleResult;
rule "annual-leave"
when
  $f: Map($f["years"] >= 1)
then
  insert(new RuleResult("leave-5d", "年假 5 天"));
end'
HID=$(rule_create leave-condition "年假规则" "$HR_DRL")
echo "HR 规则 id=$HID"; rule_enable "$HID"; sleep 32
echo "facts {years:2} → $(rule_validate $HID '{"years":2}')"
echo "facts {years:0} → $(rule_validate $HID '{"years":0}')"

echo ""
echo "========== 清理 =========="
rule_delete_key default; rule_delete_key delivery-condition; rule_delete_key leave-condition; rule_delete_key bad-rule
curl -s "http://127.0.0.1:${RULE}/admin-api/rule/page?pageNo=1&pageSize=10" -H "tenant-id: ${TENANT1}" -H "Authorization: Bearer ${T1}" | python3 -c "
import sys,json
print('残留规则数:', (json.load(sys.stdin)['data']['total']))" 2>/dev/null
