#!/bin/bash
# 启动本地 BGE reranker 服务(llama.cpp, arm64 原生)
# 依赖: brew install llama.cpp; 模型: ~/.lmstudio/models/gpustack/bge-reranker-v2-m3-GGUF/bge-reranker-v2-m3-Q8_0.gguf
# 用法: bash deploy/llama-rerank.sh   (端口 1236)
pkill -f "llama-server.*bge-reranker" 2>/dev/null
nohup llama-server \
  -m /Users/wudi/.lmstudio/models/gpustack/bge-reranker-v2-m3-GGUF/bge-reranker-v2-m3-Q8_0.gguf \
  --port 1236 --reranking -ub 2048 > /tmp/llama-rerank.log 2>&1 &
echo "llama-server(bge-reranker) 已启动, 端口 1236, physical-batch-size(-ub)=2048; 验证: curl -X POST http://127.0.0.1:1236/v1/rerank -H 'Content-Type: application/json' -d '{\"query\":\"x\",\"documents\":[\"a\",\"b\"]}'"
