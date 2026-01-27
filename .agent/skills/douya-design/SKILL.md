---
name: douya-design
description: Design and implement the core intelligence, architecture, and logic patterns for the Douya (豆芽) AI Agent system. Focus on hierarchical memory, agentic RAG, and multi-agent orchestration via Supervisor graphs.
---

This skill defines the architectural soul and operational logic of **Douya (豆芽)**. It guides how agents think, remember, and collaborate to solve complex tasks. Use this when designing new services, agents, or data flows within the ecosystem.

## Design Philosophy: "Integrated Intelligence"

Douya is not just a chatbot; it is a **coordinated brain**. Its design prioritizes:
- **Clarity over Complexity**: Every agent has a distinct mission.
- **Memory as a Foundation**: Intelligence is nothing without context.
- **Traceable Reasoning**: The "how" is as important as the "what".

## Core Architectural Pillars

### 1. Hierarchical Memory Management
Design data flows that respect the three tiers of memory:
- **L1 (Reactive)**: Volatile, zero-latency conversation context stored in JVM/Memory.
- **L2 (Persistent)**: Historical logs and structured session states in PostgreSQL/Redis.
- **L3 (Knowledge)**: Semantically indexed expert knowledge in Chroma (Vector DB).

### 2. Multi-Agent Orchestration (Supervisor Pattern)
When designing complex workflows, use the **StateGraph** approach:
- **The Supervisor**: Acts as the router and decision-maker. It maintains the "Global State".
- **Expert Workers**: Decoupled agents (e.g., Vision, RAG, Calculator) that perform specific tasks and return results to the Supervisor.
- **Loop Prevention**: Always design explicit termination conditions (`FINISH` state) and cycle detection.

### 3. Agentic RAG Framework
Move beyond "Naive RAG". In Douya, retrieval is an **action**:
- **On-demand Retrieval**: Agents should *decide* when to search based on user intent.
- **Multi-modal Linkage**: Associate retrieved text with media (OSS URLs) to provide rich, grounded answers.
- **Parent-Child Splitting**: Use Child chunks for precise hits and Parent chunks for full semantic context.

## Logic & Interaction Guidelines

- **Think-Act-Reflect**: Always implement a pattern where the AI validates its tools' outputs before presenting the final answer to the user.
- **Preference Learning**: Design silent observers (Hooks/Interceptors) that extract user preferences from conversation streams without interrupting the flow.
- **Tool-First Design**: Focus on building robust, modular tools that can be shared across multiple agents.

## Implementation Principles

- **KISS (Keep It Simple, Stupid)**: Avoid over-engineering. If a simple prompt can solve it, don't build a complex graph.
- **Stateless Services**: The backend should target horizontal scalability; keep per-request state within the dedicated Memory Store.
- **Observability**: Every agentic decision should be logged with unique Trace IDs for debugging the "black box" of LLM reasoning.

## Personality & Branding: "Douya (豆芽)"
The name "Douya" represents **Growth and Vitality**. The system should feel:
- **Lightweight**: Fast response, minimal overhead.
- **Nourishing**: Providing high-value, accurate information.
- **Evolving**: Getting smarter with every interaction through its preference learning and knowledge accumulation.

**CRITICAL**: Every architectural choice must enhance the agent's ability to act autonomously and accurately. If it's just a static AI, it's not Douya.
