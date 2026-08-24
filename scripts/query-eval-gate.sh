#!/usr/bin/env sh
set -eu

# P0 Query Capability Gate
# Offline/deterministic tests only: no real LLM/ES/Milvus required.
# Run from repository root: sh scripts/query-eval-gate.sh

COMMON="-DskipITs -Dsurefire.failIfNoSpecifiedTests=false"

echo "[1/3] evidence planner / structured / scope / semantics gate"
mvn -pl yudao-module-evidence/yudao-module-evidence-server -am $COMMON \
  -Dtest='QueryCapabilityGoldenMatrixTest,EvidenceQueryScopeResolverTest,CompositeQueryExecutorPlannerTest,CompositeQueryExecutorExactTextTest,CompositeQueryExecutorTest,MultiFieldProjectionServiceTest,StructuredFilterTreeTest,StructuredQueryEngineCoreTest,ExactTextExecutionServiceTest' \
  test

echo "[2/3] retrieval exact-text gate"
mvn -pl yudao-module-retrieval/yudao-module-retrieval-server -am $COMMON \
  -Dtest='ExactTextRetrievalServiceTest,QueryAnalysisServiceTest' \
  test

echo "[3/3] knowledge ACL gate"
mvn -pl yudao-module-knowledge/yudao-module-knowledge-server -am $COMMON \
  -Dtest='KnowledgeApiImplPermissionTest' \
  test

echo "P0 query capability gate passed."
