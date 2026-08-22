# Memory Changelog

## 2026-08-16 — Initial analysis
- Full codebase analyzed and memory files written
- 20 modules mapped, 527 endpoints documented, 65 models captured

### Session ended at 11:05

**Recent commits:**
```
4cd1724 框架修改
21652d7 框架修改
6a51ccb 初始化导入
```

## 2026-08-16 — Session update (11:05)
- No structural changes detected

### Git activity
```
4cd1724 框架修改
21652d7 框架修改
6a51ccb 初始化导入
```


### Session ended at 19:13
### Session ended at 19:13


**Git diff:**
**Uncommitted changes:**
```
```
 .claude/rules/changelog.md | 4 ++++
 1 file changed, 4 insertions(+)
 .claude/rules/changelog.md | 4 ++++
 1 file changed, 4 insertions(+)
```
```

**Recent commits:**

```
**Recent commits:**
056c090 fix: P0 评审问题修复——父子切分 parentId 回填真实DB id + 版本过期级联清理检索索引
2c4247b docs: MinerU/视觉模型部署说明修正(M1实测: Docker ollama 无 Metal 慢, macOS 推荐 LM Studio)
21e7beb fix: MinerU 部署方式修正(官方无 Docker Hub 镜像, 改 pip+mineru-api/社区镜像); ollama 纳入 compose 管理
ba821c1 test: 切分插件核心逻辑单元测试(结构切分标题链/auto判定/策略注册/参数合并) + spring-boot-starter-test 依赖
5cc34f0 docs: 上下文交接更新(模型DB化+切分策略改造交付)
```
```
056c090 fix: P0 评审问题修复——父子切分 parentId 回填真实DB id + 版本过期级联清理检索索引
2c4247b docs: MinerU/视觉模型部署说明修正(M1实测: Docker ollama 无 Metal 慢, macOS 推荐 LM Studio)
21e7beb fix: MinerU 部署方式修正(官方无 Docker Hub 镜像, 改 pip+mineru-api/社区镜像); ollama 纳入 compose 管理
ba821c1 test: 切分插件核心逻辑单元测试(结构切分标题链/auto判定/策略注册/参数合并) + spring-boot-starter-test 依赖
5cc34f0 docs: 上下文交接更新(模型DB化+切分策略改造交付)
```

## 2026-08-22 — Session update (19:13)
- Added 2 new module(s): deploy, docs
- Added 121 new route file(s)
- Added 34 new model file(s)

### Git activity
```
056c090 fix: P0 评审问题修复——父子切分 parentId 回填真实DB id + 版本过期级联清理检索索引
2c4247b docs: MinerU/视觉模型部署说明修正(M1实测: Docker ollama 无 Metal 慢, macOS 推荐 LM Studio)
21e7beb fix: MinerU 部署方式修正(官方无 Docker Hub 镜像, 改 pip+mineru-api/社区镜像); ollama 纳入 compose 管理
ba821c1 test: 切分插件核心逻辑单元测试(结构切分标题链/auto判定/策略注册/参数合并) + spring-boot-starter-test 依赖
5cc34f0 docs: 上下文交接更新(模型DB化+切分策略改造交付)
```
```
.claude/rules/api.md                               | 123 ++++++++++++++++++
 .claude/rules/changelog.md                         |  34 +++++
 .claude/rules/models.md                            |  36 ++++++
 .claude/rules/modules.md                           |  23 ++++
 deploy/mineru/README.md                            |  53 ++++----
 deploy/yudao-cloud-dev/docker-compose.yml          |  44 +++----
 docs/上下文交接.md                                 |  27 ++++
 .../yudao/module/ingestion/api/IngestionApi.java   |  10 ++
 .../yudao-module-ingestion-server/pom.xml          |   6 +
 .../module/ingestion/api/IngestionApiImpl.java     |  23 ++++
 .../ingestion/service/IngestServiceImpl.java       |  19 ++-
 .../ingestion/split/ParentChildSplitter.java       |   6 +-
 .../ingestion/split/StructureSplitterTest.java     | 138 +++++++++++++++++++++
 .../version/impl/AiDocVersionServiceImpl.java      |  18 +++
 14 files changed, 503 insertions(+), 57 deletions(-)
```


### Session ended at 19:14
### Session ended at 19:14

**Uncommitted changes:**
```

 .claude/rules/api.md       | 123 +++++++++++++++++++++++++++++++++++++++++++++
 .claude/rules/changelog.md |  69 +++++++++++++++++++++++++
 .claude/rules/models.md    |  36 +++++++++++++
 .claude/rules/modules.md   |  23 +++++++++
 CLAUDE.md                  |   2 +-
**Git diff:**
 5 files changed, 252 insertions(+), 1 deletion(-)
```
```
 .claude/rules/api.md       | 123 +++++++++++++++++++++++++++++++++++++++++++++
 .claude/rules/changelog.md |  69 +++++++++++++++++++++++++
 .claude/rules/models.md    |  36 +++++++++++++
 .claude/rules/modules.md   |  23 +++++++++
 CLAUDE.md                  |   2 +-
 5 files changed, 252 insertions(+), 1 deletion(-)

```
**Recent commits:**

```
**Recent commits:**
056c090 fix: P0 评审问题修复——父子切分 parentId 回填真实DB id + 版本过期级联清理检索索引
```
2c4247b docs: MinerU/视觉模型部署说明修正(M1实测: Docker ollama 无 Metal 慢, macOS 推荐 LM Studio)
21e7beb fix: MinerU 部署方式修正(官方无 Docker Hub 镜像, 改 pip+mineru-api/社区镜像); ollama 纳入 compose 管理
ba821c1 test: 切分插件核心逻辑单元测试(结构切分标题链/auto判定/策略注册/参数合并) + spring-boot-starter-test 依赖
5cc34f0 docs: 上下文交接更新(模型DB化+切分策略改造交付)
056c090 fix: P0 评审问题修复——父子切分 parentId 回填真实DB id + 版本过期级联清理检索索引
2c4247b docs: MinerU/视觉模型部署说明修正(M1实测: Docker ollama 无 Metal 慢, macOS 推荐 LM Studio)
```
21e7beb fix: MinerU 部署方式修正(官方无 Docker Hub 镜像, 改 pip+mineru-api/社区镜像); ollama 纳入 compose 管理
ba821c1 test: 切分插件核心逻辑单元测试(结构切分标题链/auto判定/策略注册/参数合并) + spring-boot-starter-test 依赖
5cc34f0 docs: 上下文交接更新(模型DB化+切分策略改造交付)
```

## 2026-08-22 — Session update (19:14)
- No structural changes detected

### Git activity
```
056c090 fix: P0 评审问题修复——父子切分 parentId 回填真实DB id + 版本过期级联清理检索索引
2c4247b docs: MinerU/视觉模型部署说明修正(M1实测: Docker ollama 无 Metal 慢, macOS 推荐 LM Studio)
21e7beb fix: MinerU 部署方式修正(官方无 Docker Hub 镜像, 改 pip+mineru-api/社区镜像); ollama 纳入 compose 管理
ba821c1 test: 切分插件核心逻辑单元测试(结构切分标题链/auto判定/策略注册/参数合并) + spring-boot-starter-test 依赖
5cc34f0 docs: 上下文交接更新(模型DB化+切分策略改造交付)
```
```
.claude/rules/api.md                               | 123 ++++++++++++++++++
 .claude/rules/changelog.md                         | 107 ++++++++++++++++
 .claude/rules/models.md                            |  36 ++++++
 .claude/rules/modules.md                           |  23 ++++
 CLAUDE.md                                          |   2 +-
 deploy/mineru/README.md                            |  53 ++++----
 deploy/yudao-cloud-dev/docker-compose.yml          |  44 +++----
 docs/上下文交接.md                                 |  27 ++++
 .../yudao/module/ingestion/api/IngestionApi.java   |  10 ++
 .../yudao-module-ingestion-server/pom.xml          |   6 +
 .../module/ingestion/api/IngestionApiImpl.java     |  23 ++++
 .../ingestion/service/IngestServiceImpl.java       |  19 ++-
 .../ingestion/split/ParentChildSplitter.java       |   6 +-
 .../ingestion/split/StructureSplitterTest.java     | 138 +++++++++++++++++++++
 .../version/impl/AiDocVersionServiceImpl.java      |  18 +++
 15 files changed, 577 insertions(+), 58 deletions(-)
```
