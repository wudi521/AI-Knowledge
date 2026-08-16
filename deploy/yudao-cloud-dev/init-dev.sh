#!/usr/bin/env bash
# ============================================================
# 芋道 yudao-cloud-mini(JDK17)开发环境初始化脚本
# 功能:等中间件就绪 → 导入芋道 SQL → 创建 Nacos namespace=dev
#       →(可选)MinIO 桶 / Kafka 主题
# 用法: cd deploy/yudao-cloud-dev && bash init-dev.sh
# 说明:幂等,可重复执行
# ============================================================
set -euo pipefail

COMPOSE_FILE="docker-compose.yml"
cd "$(dirname "$0")"

# 芋道仓库 SQL 目录(按实际路径修改)
REPO_SQL_DIR="${REPO_SQL_DIR:-/Users/wudi/IdealProjects/myself/yudao-cloud-mini-master-jdk17/sql/mysql}"
MYSQL_PWD="123456"
MYSQL_DB="ruoyi-vue-pro"
NACOS_NS="dev"

echo "==> [1/5] 启动中间件(必需: mysql/redis/nacos, 含 minio)"
docker compose -f "$COMPOSE_FILE" up -d

echo "==> [2/5] 等待中间件就绪"
wait_ready() {
  local name="$1"; local cmd="$2"; local tries="${3:-30}"
  for i in $(seq 1 "$tries"); do
    if docker compose -f "$COMPOSE_FILE" exec -T "$name" sh -c "$cmd" >/dev/null 2>&1; then
      echo "    - $name: OK"; return 0
    fi
    sleep 5
  done
  echo "    - $name: FAILED(超时,查看 docker compose logs $name)"; return 1
}

wait_ready mysql "mysqladmin ping -uroot -p${MYSQL_PWD} --silent" 30
wait_ready redis "redis-cli ping | grep -q PONG" 30
wait_ready nacos "curl -s http://localhost:8848/nacos/v1/console/health/readiness | grep -qiE 'ok|up'" 40

echo "==> [3/5] 导入芋道 SQL(幂等:已有表则跳过)"
if [ ! -d "$REPO_SQL_DIR" ]; then
  echo "    ! 找不到 SQL 目录: $REPO_SQL_DIR"
  echo "    ! 请修改脚本顶部 REPO_SQL_DIR 指向芋道仓库的 sql/mysql 目录"
else
  # 业务表:ruoyi-vue-pro.sql
  if docker compose -f "$COMPOSE_FILE" exec -T mysql sh -c \
     "mysql -uroot -p${MYSQL_PWD} -N -e \"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${MYSQL_DB}' AND table_name='system_users'\" | grep -q 1"; then
    echo "    - 业务表已存在,跳过 ruoyi-vue-pro.sql"
  else
    docker compose -f "$COMPOSE_FILE" exec -T mysql sh -c "exec mysql -uroot -p${MYSQL_PWD} ${MYSQL_DB}" < "$REPO_SQL_DIR/ruoyi-vue-pro.sql" \
      && echo "    - ruoyi-vue-pro.sql: 导入完成" || echo "    - ruoyi-vue-pro.sql: 导入失败,请检查"
  fi
  # 定时任务表:quartz.sql
  if docker compose -f "$COMPOSE_FILE" exec -T mysql sh -c \
     "mysql -uroot -p${MYSQL_PWD} -N -e \"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${MYSQL_DB}' AND table_name='QRTZ_JOB_DETAILS'\" | grep -q 1"; then
    echo "    - 定时任务表已存在,跳过 quartz.sql"
  else
    docker compose -f "$COMPOSE_FILE" exec -T mysql sh -c "exec mysql -uroot -p${MYSQL_PWD} ${MYSQL_DB}" < "$REPO_SQL_DIR/quartz.sql" \
      && echo "    - quartz.sql: 导入完成" || echo "    - quartz.sql: 导入失败,请检查"
  fi
fi

echo "==> [4/5] 创建 Nacos 命名空间 ${NACOS_NS}(幂等)"
# 注: --noproxy '*' 避免宿主机 HTTP 代理(如 7890)干扰 localhost 访问
if curl --noproxy '*' -s "http://127.0.0.1:8848/nacos/v1/console/namespaces" | grep -q '"namespaceId":"'"$NACOS_NS"'"' 2>/dev/null; then
  echo "    - 命名空间 ${NACOS_NS}: 已存在,跳过"
else
  if curl --noproxy '*' -s -X POST "http://127.0.0.1:8848/nacos/v1/console/namespaces" \
     -d "customNamespaceId=${NACOS_NS}&namespaceName=${NACOS_NS}&namespaceDesc=开发环境" | grep -q "true"; then
    echo "    - 命名空间 ${NACOS_NS}: 创建成功"
  else
    echo "    - 命名空间 ${NACOS_NS}: 创建失败,请到 Nacos 控制台手动创建(customNamespaceId=dev)"
  fi
fi

echo "==> [5/5] 可选资源(失败不影响主流程)"
# MinIO 桶:kb-docs(AI 链路文件)
if docker compose -f "$COMPOSE_FILE" ps minio >/dev/null 2>&1; then
  docker compose -f "$COMPOSE_FILE" exec -T minio sh -c \
    "mc alias set local http://localhost:9000 minioadmin minioadmin123 >/dev/null 2>&1 && mc mb --ignore-existing local/kb-docs >/dev/null 2>&1" \
    && echo "    - MinIO bucket 'kb-docs': OK" || echo "    - MinIO bucket 'kb-docs': 失败(可忽略)"
fi

echo ""
echo "    就绪信息:"
echo "      MySQL : localhost:3306  db=ruoyi-vue-pro  root/123456"
echo "      Redis : localhost:6379"
echo "      Nacos : localhost:8848/nacos  nacos/nacos  命名空间 dev"
echo "      MinIO : localhost:9001  minioadmin/minioadmin123"
echo ""
echo "    下一步:IDEA 启动 gateway-server(48080) → system-server(48081) → infra-server(48082)"
echo "            再启动前端 yudao-ui-admin-vue3,登录 admin/admin123"
echo "    详见《5-部署清单-开发环境-芋道Cloud版.md》第 7~9 节"
echo ""
echo "    初始化完成"
