---
name: douya-design
description: Design, evolve, and troubleshoot Douya's backend AI architecture (Spring Boot + Spring AI Alibaba), including hierarchical memory (L1/L2/L3), Supervisor-based multi-agent orchestration, Agentic RAG, Feishu integration, and PageIndexRAG. Use when Codex needs to define architecture, add/modify agent flows, optimize retrieval quality, or resolve model/tool/vector-store integration issues in this repository.
---

# Douya Design Skill

Use this skill to make architecture and implementation decisions that stay consistent with the repository's actual design.

## Load References On Demand

1. For retrieval architecture, chunking, fallback, and citation/media preservation, read `references/rag-playbook.md`.
2. For multi-agent routing, handoff rules, loop-stop strategies, and finish conditions, read `references/supervisor-routing.md`.
3. For L1/L2/L3 memory decisions and production migration paths, read `references/memory-migration.md`.
4. Load only the file that matches the current task category. Avoid loading all references by default.

## Follow This Baseline

1. Target stack:
- Spring Boot 3.5.x, Java 21, Maven.
- Spring AI Alibaba + DashScope as primary model/embedding path.
- Chroma as default vector store.
- Feishu platform integration for message/event flow.
2. Respect DDD layering:
- `application`: orchestration, graph, hooks, interceptors.
- `domain`: business capability (e.g., eating/pdf logic).
- `infrastructure`: external systems, vector store, persistence, tools.
- `interfaces`: HTTP/web adapters.
3. Preserve portability:
- Keep skill/prompt behavior in skill files where possible.
- Keep infra wiring in config classes, not in domain logic.

## Execute Design Work In This Order

1. Classify the requested change:
- `agent-flow`: routing, handoff, graph nodes, loop handling.
- `memory`: L1/L2/L3 storage, hydration, preference persistence.
- `rag`: ingestion, chunking, retrieval, rerank, citation/media retention.
- `integration`: model provider, Feishu, OSS, MCP/web search integration.
2. Load exactly one matching reference file from `references/` first, then expand only if blocked.
3. Map to code ownership:
- Graph/supervisor: `application/graph`.
- Agent app service: `application/service`.
- Retrieval tools: `infrastructure/tool` and `infrastructure/vectorstore`.
- Persistence implementation: `infrastructure/persistence`.
- API contract/controller: `interfaces/web`.
4. Propose minimum safe change first:
- Prefer local, composable changes.
- Avoid cross-layer rewrites unless current design is blocked.
5. Add observability in the same patch:
- Add traceable logs around routing, retrieval hit/miss, and fallback decisions.

## Apply Douya Architectural Rules

1. Keep three-tier memory explicit:
- L1: short-lived context for active thread.
- L2: persistent session/history store for recovery.
- L3: semantic knowledge retrieval (vector search).
2. Use Agentic RAG, not blind injection:
- Retrieve only when intent requires memory/knowledge.
- If local retrieval is empty, trigger fallback path (typically web search when allowed).
3. Preserve parent-child retrieval semantics:
- Use child chunks for recall, parent context for generation.
- Keep metadata needed for source/citation and media linkage.
4. Enforce Supervisor termination:
- Define explicit finish condition.
- Guard against repeated expert bouncing loops.
5. Preserve image/media assets through the chain:
- Do not drop OSS/media URL signals returned by tools.
- Keep formatter compatibility in mind when changing output format.
6. Keep user preference flow silent and automatic:
- Load preferences in interceptor/hook before model call.
- Store learned preferences after response when confidence is sufficient.

## Guardrails For Common Risk Areas

1. Embedding model conflicts:
- If multiple model starters are enabled, explicitly qualify the embedding bean.
- Disable unintended embedding autoconfiguration for secondary provider.
2. Provider path mismatches:
- For OpenAI-compatible providers, verify base URL vs completions path behavior.
3. Memory store migration:
- For non-memory stores, ensure schema/dependency readiness before switching.
- Keep startup fallback strategy clear to avoid runtime breakage.
4. Retrieval quality regressions:
- Validate threshold/top-k changes with before/after examples.
- Check short-query recall and long-context noise together.
5. Feishu event SLA:
- Keep event acknowledgment path fast; move heavy inference to async flow.

## Validate Every Architecture Change

1. Verify startup path:
- Application boots with selected profile and required external services.
2. Verify one golden flow per affected domain:
- Example: image upload -> vision expert -> supervisor -> eating expert.
3. Verify retrieval behavior:
- Hit case, miss case, and fallback case.
4. Verify structured output path:
- Ensure downstream formatter/renderer still receives expected markers.
5. Record constraints in code comments only where non-obvious:
- Explain why a fragile config or threshold must remain.

## Keep Scope Tight

1. Do not introduce new frameworks when existing stack can solve the issue.
2. Do not mix UI/brand writing into architecture changes.
3. Do not add extra docs files unless explicitly requested.
4. Keep SKILL instructions concise and actionable.
