# 中间件清单:本机 Docker 安装结果

> 版本:v1.2 · 2026-08-16 · 状态:**10/10 全部 healthy,初始化完成,AI 表已建**
> 环境:Mac(Apple Silicon / arm64)· Docker Server 29.6.2 · compose 项目 `yudao-cloud-dev`
> 编排文件:`deploy/yudao-cloud-dev/docker-compose.yml`(已从旧工作目录收编到项目内) · 初始化脚本:`deploy/yudao-cloud-dev/init-dev.sh`

---

## 1. 中间件清单(10 个容器,全部运行中)

| # | 服务 | 镜像(arm64 适配) | 端口(主机) | 账号/密码 | 状态 |
|---|------|-----------------|-----------|----------|------|
| 1 | mysql | mysql:8.0 | 3306 | root / 123456(库 ruoyi-vue-pro) | ✅ healthy |
| 2 | redis | redis:7.0-alpine | 6379 | 无 | ✅ healthy |
| 3 | nacos | **nacos/nacos-server:v2.4.3**(2.3.x 无 arm64) | 8848 / 9848 | nacos / nacos,命名空间 **dev** | ✅ healthy |
| 4 | minio | minio/minio:latest | 9000 / 9001 | minioadmin / minioadmin123 | ✅ healthy |
| 5 | elasticsearch | **docker.elastic.co/...:8.13.4**(Docker Hub 版无 arm64) | 9200 | 关安全,单节点 green | ✅ healthy |
| 6 | **kibana** | **docker.elastic.co/kibana/kibana:8.13.4** | **5601** | 无(界面中文) | ✅ healthy |
| 7 | kafka | apache/kafka:3.7.1(实测 arm64 可用,文档 5 的 3.9.0 为误记) | 9092 | PLAINTEXT,KRaft 单节点,`hostname: yudao-kafka` | ✅ healthy |
| 8 | milvus | milvusdb/milvus:v2.4.13 | 19530(gRPC)/ 9091(健康) | 无鉴权 | ✅ healthy |
| 9 | milvus-etcd | quay.io/coreos/etcd:v3.5.14 | 内部 | - | ✅ healthy |
| 10 | milvus-minio | minio/minio:latest | 内部 | minioadmin / minioadmin | ✅ healthy |

**Kibana 使用**:浏览器打开 `http://localhost:5601` → 左侧 **Dev Tools** → Console 里直接写 ES DSL 查询(如 `GET _cat/indices?v`),比 curl 直观。首次进入跳过引导即可(ES 未开安全)。

数据卷(持久化,`docker compose down` 不丢失):mysql_data、redis_data、minio_data、es_data、kafka_data、etcd_data、milvus_minio_data、milvus_data。

## 2. 初始化结果(已执行,幂等可重跑)

| 项 | 结果 |
|----|------|
| MySQL 业务表 | ✅ 59 张(system/infra 等,ruoyi-vue-pro.sql + quartz.sql) |
| AI 业务表(ai_*) | ✅ **14 张已导入**(knowledge 4 + chat 3 + evidence 1 + eval 2 + agent 2 + governance 2),库总计 **73 表** |
| Nacos 命名空间 | ✅ `dev` 已创建 |
| Kafka 主题 | ✅ 4 个:knowledge-ingest / chat-events / eval-tasks / audit-events(各 3 分区) |
| MinIO 桶 | ✅ `kb-docs`(AI 文档存储) |
| Kibana | ✅ 5601,可连 ES(Dev Tools 查询) |

## 3. 验证命令(注意:本机有 HTTP 代理 127.0.0.1:7890,curl 需加 `--noproxy '*'`)

```bash
docker compose ps                                            # 全部 healthy
curl --noproxy '*' -s http://127.0.0.1:8848/nacos/v1/console/health/readiness   # OK
curl --noproxy '*' -s http://127.0.0.1:9200/_cluster/health  # green
curl --noproxy '*' -s http://127.0.0.1:9091/healthz          # OK
curl --noproxy '*' -s http://127.0.0.1:9000/minio/health/live
```

## 4. 常用命令

```bash
cd deploy/yudao-cloud-dev        # 已收编到项目内,不再用旧工作目录
docker compose up -d             # 启动全部
docker compose ps                # 状态
docker compose logs -f nacos     # 看日志
bash init-dev.sh                 # 重跑初始化(幂等)
docker compose down              # 停止(保留数据)
docker compose down -v           # 停止并清空数据(重来)
```

## 5. 踩坑记录(供后续参考)

| 问题 | 原因 | 解决 |
|------|------|------|
| 拉镜像报 `no matching manifest for linux/arm64/v8` | Apple Silicon 无 arm64 镜像 | nacos→v2.4.3、ES→docker.elastic.co、kafka→3.7.1(实测有 arm64)、minio→latest |
| 主机 curl localhost 返回 502 | 本机 HTTP 代理(7890)拦截 | curl 加 `--noproxy '*'`;Java 服务不受影响 |
| minio 容器 unhealthy | 容器内无 curl,healthcheck 失败 | healthcheck 改用 `bash -c '</dev/tcp/127.0.0.1/9000'` |
| nacos unhealthy | readiness 返回大写 `OK`,`grep -q ok` 不匹配 | healthcheck/脚本改 `grep -qiE 'ok|up'` |
| init 脚本建 Nacos 命名空间失败 | 宿主机 curl 走代理 | curl 加 `--noproxy '*'` |
| **Kibana 一直"尚未就绪"** | **Docker 虚拟磁盘满(镜像 26GB+构建缓存 37GB)→ ES 磁盘水位拒绝分配分片 → 集群 red → .kibana 无法 green → Kibana 迁移超时** | 清理 `docker builder prune -f && docker image prune -f` 释放 ~16GB → ES 恢复 green → Kibana 自动可用 |
| **Kafka 启动即退出(Exited 255/1)** | **KRaft 模式下容器 hostname 是随机容器 ID(如 `446025cc0830`),Java 解析自身 hostname 失败: `UnknownHostException: <容器ID>`** | compose 的 kafka 服务加 `hostname: yudao-kafka` 后 `docker compose up -d kafka` 重建;数据卷保留,主题需重建(4 个:`knowledge-ingest`/`chat-events`/`eval-tasks`/`audit-events`,各 3 分区) |

## 6. 下一步

1. AI 表建表:执行 6 个模块的 `sql/*.sql`(knowledge/chat/evidence/eval/agent/governance,共 14 张 ai_* 表)✅ 已完成
2. IDEA 启动:gateway(48080)→ system(48081)→ infra(48082)→ AI 模块(48083~48093)✅ 已启动 gateway/system/infra/knowledge/model
3. 前端 yudao-ui-admin-vben(独立仓库,vben5)登录 admin / admin123
4. **入库管线开发**:ingestion-server 消费 `knowledge-ingest` → 解析(Tika/PDFBox/POI/Tesseract OCR)→ 五策略切分 → BGE-M3 向量化(LM Studio 127.0.0.1:1234)→ 三写(MySQL ai_chunk + Milvus + ES BM25),详见《9-入库管线设计.md》
