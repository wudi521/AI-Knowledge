#!/usr/bin/env bash
# ============================================================
# 双回答者收敛 + ChatPipeline 反问闭环 验证 (2026-08-21)
# 前置: retrieval-server(48086) + chat-server(48083) 已重启加载新代码
# 用法: bash deploy/clarify-verify.sh
# ============================================================
set -e
BASE=http://127.0.0.1
GATEWAY=$BASE:48080
RETRIEVAL=$BASE:48086
CHAT=$BASE:48083
SYS=$BASE:48081

echo "==> 登录(tenant 1)"
TOKEN=$(curl -s "$SYS/admin-api/system/auth/login" -H 'Content-Type: application/json' -H 'tenant-id: 1' \
  -d '{"username":"admin","password":"admin123"}' | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["accessToken"])')
AUTH="Authorization: Bearer $TOKEN"
T1="tenant-id: 1"
pass=0; fail=0

check() { # name expected_grep actual
  if echo "$3" | grep -q "$2"; then echo "  ✅ $1"; pass=$((pass+1));
  else echo "  ❌ $1"; echo "     实际: $(echo "$3" | head -c 200)"; fail=$((fail+1)); fi
}

echo ""
echo "==> CR-01 检索接口收敛: /retrieval/search 不再返回 answer(恒 null)"
R=$(curl -s -X POST "$RETRIEVAL/admin-api/retrieval/search" -H "$AUTH" -H "$T1" -H 'Content-Type: application/json' \
  -d '{"query":"手机碎屏了怎么办","kbIds":[1],"topK":5}')
check "检索 answer 为 null" '"answer":null' "$R"
check "检索结果非空" '"results"' "$R"

echo ""
echo "==> CR-02 证据评估仍正常产出 answer(统一链路)"
R=$(curl -s -X POST "$BASE:48087/admin-api/evidence/evaluate" -H "$AUTH" -H "$T1" -H 'Content-Type: application/json' \
  -d '{"query":"手机碎屏了怎么办","kbIds":[1],"topK":8}')
check "证据评估 answer 非空" 'answer' "$R"

echo ""
echo "==> CR-03 对话反问闭环: 缺槽位问题 → 返回反问而非转人工"
# 新建会话并发缺槽位问题("我的手机坏了") — KB1 必填 brand/product/faultType/purchaseTime
R=$(curl -s -X POST "$CHAT/admin-api/chat/chat/send" -H "$AUTH" -H "$T1" -H 'Content-Type: application/json' \
  -d '{"message":"我的手机坏了,可以免费维修吗?","channel":"WEB"}')
check "返回反问问题(请补充)" '请补充' "$R"
check "transferRequired=false" '"transferRequired":false' "$R"
check "reply 非空(反问文案)" '"reply"' "$R"

echo ""
echo "==> CR-04 对话正常回答仍走原链路(槽位齐全)"
R=$(curl -s -X POST "$CHAT/admin-api/chat/chat/send" -H "$AUTH" -H "$T1" -H 'Content-Type: application/json' \
  -d '{"message":"苹果13摔碎屏了,刚买一个月,请问怎么处理","channel":"WEB"}')
check "正常回答非空" 'answerable' "$R"

echo ""
echo "结果: $pass 通过 / $fail 失败"
[ "$fail" -eq 0 ]
