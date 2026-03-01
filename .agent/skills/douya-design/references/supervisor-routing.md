# Supervisor Routing

## Scope

Use this file when changing multi-agent orchestration, graph routing, expert handoff, loop prevention, or finish behavior.

## Routing Model

1. Supervisor owns global task state.
2. Experts execute narrow domain work and return structured result.
3. Supervisor decides next node or `FINISH`.

## Execution Workflow

1. Define intent categories before coding:
- `chat-general`
- `knowledge-retrieval`
- `visual-analysis`
- `multi-step-composition`
2. Map each category to a primary expert and optional fallback.
3. Define explicit stop conditions:
- Result quality is sufficient.
- Max handoff count reached.
- No new evidence from next tool/agent step.
4. Add anti-loop guard:
- Track previous node sequence.
- Stop when repeating pattern exceeds threshold.

## State Requirements

1. Track:
- user input
- rewritten prompt (if rewriter is used)
- tool outputs
- current expert
- handoff count
- finish reason
2. Keep state fields minimal and serializable.

## Handoff Rules

1. Handoff only when missing capability or missing evidence.
2. Do not handoff only for style rewriting.
3. Prefer one high-quality expert pass over many shallow passes.
4. If visual asset exists and user asks image-dependent question, route to vision expert first.

## Failure Handling

1. Tool error:
- Return controlled fallback text and route decision note.
2. Empty retrieval:
- Route to web/search-capable expert when policy allows.
3. Ambiguous user request:
- Ask one concise clarification instead of agent bouncing.

## Observability

1. Log routing decision with reason and confidence.
2. Log handoff transitions with node names.
3. Log termination reason and total steps.

## Regression Checklist

1. Single-step tasks finish in one supervisor cycle.
2. Multi-step tasks stop with explicit finish reason.
3. No infinite loop under repeated ambiguous prompts.
4. Existing expert boundaries remain intact.
