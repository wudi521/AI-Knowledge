# 企业级改造 · 03 文件下载安全(批次 A3)

> 日期: 2026-08-22 · 对应实施规范 A3

## 1. 问题确认
- `IngestServiceImpl.downloadFromMinio` 用 hutool `HttpUtil.downloadFile` 直下任意 URL:
  SSRF(内网/云元数据)、超大文件、伪造文件类型均无防护。

## 2. 方案(下载源 allowlist)
- `DownloadGuard`: ① scheme 白名单(http/https) ② host 白名单(配置 `allowed-hosts`,
  默认 127.0.0.1,localhost; 生产配 MinIO 内网域名)——白名单优于黑名单, 不误伤内网 MinIO
  同时拒绝任意 URL 下载 ③ Content-Length 预检 + 落地后大小校验(默认 100MB)
  ④ magic number 校验(文件头与声明类型匹配: PDF/OOXML/OLE2/图片, 文本不校验)
- 校验失败: 清理临时文件后抛异常 → 文档置 FAILED

## 3. 文件
- `ingestion/parse/DownloadGuard.java`(新): validateUrl/download/validateMagic
- `IngestServiceImpl`: downloadFromMinio 接入守卫(storagePath+docType)
- ingestion `application-local.yaml`: `yudao.ingestion.download.{allowed-hosts,max-file-bytes}`

## 4. 验证
- 编译通过; 冒烟 13 项 ALL PASSED(白名单通过/非白名单拒绝/云元数据拒绝/内网拒绝/
  file·ftp scheme 拒绝/空白非法 URL/真实 PDF·OOXML magic 通过/伪造 HTML 伪装 PDF 拒绝/文本不校验)

## 5. 回滚
- 移除 `yudao.ingestion.download.*` 配置或 git revert; 守卫仅影响下载入口, 无数据迁移
