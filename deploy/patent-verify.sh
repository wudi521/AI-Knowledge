#!/usr/bin/env bash
# 专利 MVP · 12 条必测问题验证脚本(部署后执行)
# 前置: 服务已启动 + 三份 PDF 已上传入库并发布 + 已配置 YUDAO_INTERNAL_AUTH_SECRET/YUDAO_SECRET_MASTER_KEY
set -u
BASE="${BASE:-http://127.0.0.1:48080}"
TOKEN="${TOKEN:-}"
# 用法: TOKEN=$(curl .../system/auth/login ...) patent-verify.sh 或配置环境变量

req() { # req <question> <expectedFragment>
  local q="$1" frag="$2"
  echo "== $q"
  local resp
  resp=$(curl -s -X POST "$BASE/admin-api/chat/chat/send" \
    -H "Authorization: Bearer $TOKEN" -H "tenant-id: 1" -H 'Content-Type: application/json' \
    -d "{\"message\":\"$q\",\"channel\":\"WEB\",\"kbIds\":[${KB_ID}]}")
  echo "$resp" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)['data']
    ans = d.get('reply') or ''
    ok = '$frag' in ans
    print(('PASS' if ok else 'FAIL') + ' | answerable=' + str(d.get('answerable')) + ' | answer: ' + ans[:80])
    print('  citations=' + str(d.get('citations')))
except Exception as e:
    print('ERROR', e)"
}

echo "Base: $BASE, KB_ID=${KB_ID:-?}(需设为专利知识库id)"
req "申请号 202311344028.2 的发明名称和申请人是什么？" "分区域视频和图片"
req "CN 122621758 A 一共有几项权利要求？" "7"
req "哪一份文档提出用电脑绣代替印花？" "电脑绣"
req "申请号 202311042981.1 的权利要求1主要限定了什么？" "电脑绣"
req "申请号 202311832214.0 的权利要求8引用了哪些在先权利要求？" "1"
req "粒子化磁涌装置的权利要求1包含哪些核心组成？" "磁涌"
req "三份专利分别属于什么技术领域或 IPC 方向？" "IPC"
req "哪一份专利涉及视频或图片不同区域以不同分辨率下载？" "分区域"
req "这三件专利哪一件已经获得授权？" "无法"
req "粒子化磁涌装置真的能治疗癌症吗？" "无法"
req "第二份专利中 3wt% 和 1wt% 分别出现在哪些记载中？" "wt%"
req "这三份专利在美国分别对应哪些 US 专利？" "无法"
