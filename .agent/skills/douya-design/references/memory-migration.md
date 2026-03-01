# Memory Migration

## Scope

Use this file when designing or changing L1/L2/L3 memory behavior, persistence providers, context rehydration, or preference learning storage.

## Memory Contract

1. L1 (hot): active conversation window, lowest latency.
2. L2 (cold): durable history/session state for restart recovery.
3. L3 (knowledge): semantic retrieval over indexed knowledge.

## Decision Rules

1. If requirement is low latency only, stay in L1.
2. If requirement includes restart continuity, add L2.
3. If requirement includes knowledge recall beyond chat history, add L3.
4. Do not mix L2 and L3 responsibilities in one store abstraction.

## Migration Workflow

1. Confirm target store and runtime dependency.
2. Verify schema/init path before code switch.
3. Keep old path available behind config during rollout.
4. Add rehydration test after restart.
5. Add rollback switch for production safety.

## Provider Risks

1. SQL dialect mismatch:
- Validate upsert syntax on selected engine.
2. Redis path:
- Confirm serialization format and TTL policy.
3. Vector store path:
- Confirm embedding model uniqueness and dimension compatibility.

## Preference Learning Path

1. Load known user preferences before model execution.
2. Persist newly learned preferences after response generation.
3. Deduplicate preference values while preserving useful order.
4. Do not block user response on preference write failures.

## Validation Checklist

1. Fresh session works without persistence services.
2. Restart rehydration restores expected recent context.
3. Preference injection appears in prompt path when available.
4. Retrieval path remains independent from L2 state store health.
