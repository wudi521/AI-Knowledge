-- ============================================================
-- Agentic RAG V1.1: 未校准 confidence 语义迁移
-- 日期: 2026-08-25
-- MySQL 8.0
--
-- ai_evidence_eval.confidence = NULL 表示“该执行模式尚未做数值置信度校准”。
-- 不能用 0 代替 unknown，否则会污染 V3 vs Agent 的后续离线评测。
-- ai_evidence.confidence 仍维持 NOT NULL：chunk evidence score 与会话 confidence 是不同语义。
-- ============================================================

ALTER TABLE `ai_evidence_eval`
    MODIFY COLUMN `confidence` decimal(5,4) NULL DEFAULT NULL COMMENT '置信度(0~1); NULL=未校准/不输出数值置信度';
