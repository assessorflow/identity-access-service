# AssessorFlow — Redis Store Design

> Defines all Redis key patterns, data structures, and TTL policies.
> Redis serves as the hot cache layer for active workflow state and user context.
> Durable persistence is always in PostgreSQL — Redis is a read-through/write-through cache, not the source of truth.

---

## 1. Assessor/Admin Context Cache

Cached by Identity and Access Service after login (overall.md Phase 1). Only for registered users (assessors and admins) — participants are not registered users.

**Key pattern:** `user:{user_id}`

**Type:** Hash

**Example:**
```redis
HSET user:550e8400-e29b-41d4-a716-446655440000
  user_id    "550e8400-e29b-41d4-a716-446655440000"
  workflow_id ""
  role       "assessor"
```

| Field | Type | Notes |
|-------|------|-------|
| user_id | string (UUID) | Same as key suffix |
| workflow_id | string | Empty string when no active workflow; set to `wf_9f3a21` when assessor starts a workflow (overall.md Phase 2 step 7) |
| role | string | `assessor` or `admin` |

**TTL:** Session duration (matches `sessions.expires_at` in PostgreSQL). Evicted on logout.

**Who writes:** Identity and Access Service (on login, on workflow start)
**Who reads:** All agents (to identify user and locate their active workflow)

---

## 2. Participant Session Cache

Participants are not registered users — they access the system via invitation link (overall.md Phase 9). This key tracks their session during assessment-taking.

**Key pattern:** `participant:{email}`

**Type:** Hash

**Example — participant taking an assessment:**
```redis
HSET participant:student1@email.com
  email          "student1@email.com"
  assessment_id  "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
  workflow_id    "wf_9f3a21"
  participant_id "p1a2b3c4-d5e6-7890-abcd-ef1234567890"
  started_at     "2026-03-21T14:00:00Z"
```

| Field | Type | Notes |
|-------|------|-------|
| email | string | Participant's email (same as key suffix) |
| assessment_id | string (UUID) | Which assessment they're taking |
| workflow_id | string | Originates from Orchestrator `workflows.id` |
| participant_id | string (UUID) | References `assessment_participants.id` |
| started_at | string (ISO 8601) | When participant began the assessment |

**TTL:** Assessment duration + buffer (e.g. `duration_minutes + 15 min`). Auto-expires after the time limit passes.

**Who writes:** Assessment Submission Service (when participant opens the assessment link)
**Who reads:** Assessment Submission Service (to enforce time limits and validate active session)

---

## 3. Workflow Hot State

Cached by the Orchestrator when a workflow is active. This is the fast-read copy of the `workflows` table in PostgreSQL. Every Orchestrator state transition writes to both PostgreSQL (durable) and Redis (hot).

**Key pattern:** `workflow:{workflow_id}`

**Type:** Hash

**Example — during question generation (Phase 7):**
```redis
HSET workflow:wf_9f3a21
  workflow_id           "wf_9f3a21"
  assessment_id         "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
  current_phase         "question_generation"
  current_agent         "qa-generation-agent"
  question_set_id       ""
  total_participants    4
  submissions_completed 0
  evaluations_completed 0
  reports_completed     0
  updated_at            "2026-03-21T10:30:00Z"
```

**Example — during evaluation (Phase 10), 2 of 4 participants evaluated:**
```redis
HSET workflow:wf_9f3a21
  workflow_id           "wf_9f3a21"
  assessment_id         "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
  current_phase         "evaluation"
  current_agent         "evaluator-agent"
  question_set_id       "qs_abc123"
  total_participants    4
  submissions_completed 4
  evaluations_completed 2
  reports_completed     0
  updated_at            "2026-03-21T14:15:00Z"
```

| Field | Type | Notes |
|-------|------|-------|
| workflow_id | string | Same as key suffix; matches `workflows.id` in PostgreSQL |
| assessment_id | string (UUID) | Links to assessment config |
| current_phase | string | One of: `material_validation`, `sufficiency_check`, `web_research`, `topic_extraction`, `question_generation`, `quality_validation`, `human_review`, `ready_for_distribution`, `assessment_active`, `evaluation`, `reporting`, `report_review`, `completed`, `terminated` |
| current_agent | string or null | The agent currently processing this workflow (e.g. `validator-agent`, `classification-agent`, `qa-generation-agent`). NULL when no agent is active (HITL review, waiting for participants) |
| last_reason_code | string or null | Updated on every phase transition. Latest `reason_code` from agent event |
| last_reason_message | string or null | Updated on every phase transition. Human-readable explanation |
| question_set_id | string (UUID) | Empty until Q&A generation is approved; set after Phase 7a |
| total_participants | integer | Total number of participants in this assessment |
| submissions_completed | integer | Incremented per participant submission (Phase 9). Used to determine when all group members have submitted before triggering group-level evaluation |
| evaluations_completed | integer | Incremented per participant evaluation completion (Phase 10 completeness check) |
| reports_completed | integer | Incremented per participant report completion (Phase 11 completeness check) |
| updated_at | string (ISO 8601) | Last state transition timestamp |

**TTL:** Set a TTL (e.g. 30 minutes) on every write. The key auto-expires if no events arrive within that window. This handles long idle periods where the workflow is waiting on humans:
- Phase 8 (HITL) — assessor may take hours/days to review questions
- Phase 9 — participants may not take the assessment for days after receiving the link
- Between phases — waiting for all participants to submit

When the key expires, no data is lost — PostgreSQL `workflows` table is always the durable source of truth.

**Who writes:** Orchestrator Agent (on every phase transition — refreshes TTL)
**Who reads:** Orchestrator Agent (to determine current state before routing)

**Cache-aside pattern:**
```
Orchestrator receives event
  → Check Redis for workflow:{workflow_id}
  → If HIT  → read from Redis (fast path)
  → If MISS → read from PostgreSQL, write to Redis with TTL (cache reload)
  → Process event, update state
  → Write to PostgreSQL (durable, always)
  → Write to Redis with TTL (hot cache, best-effort)
```

This means Redis is only populated during active processing bursts. During idle phases (HITL review, waiting for participants), the key expires and memory is freed. When the next event arrives, the Orchestrator reloads from PostgreSQL.

---

## 4. Orchestrator Checkpoint (LangGraph RedisSaver)

Used by the Orchestrator's LangGraph StateGraph for checkpoint/resume. When the Orchestrator publishes an async Pub/Sub event and suspends, the graph state is checkpointed to Redis. When the completion event arrives, the Orchestrator resumes from the checkpoint.

**Key pattern:** `checkpoint:{workflow_id}:{thread_id}`

**Type:** String (serialised LangGraph checkpoint)

**Managed by:** LangGraph `RedisSaver` — the Orchestrator does not read/write these keys directly.

**TTL:** Same lifecycle as the workflow. Cleaned up on workflow completion.

**Who writes:** LangGraph RedisSaver (automatic on graph suspend)
**Who reads:** LangGraph RedisSaver (automatic on graph resume)

> This is an implementation detail of the Orchestrator's stateful design (ADR-29/36). No other agent interacts with checkpoint keys.

---

## 5. Summary

| Key Pattern | Owner | Purpose | TTL |
|-------------|-------|---------|-----|
| `user:{user_id}` | Identity and Access Service | Assessor/admin context (role, active workflow) | Session duration |
| `participant:{email}` | Assessment Submission Service | Participant session during assessment-taking | Assessment duration + buffer |
| `workflow:{workflow_id}` | Orchestrator Agent | Hot workflow state (phase, progress counters) | TTL refreshed on activity; expires during idle |
| `checkpoint:{workflow_id}:{thread_id}` | LangGraph RedisSaver | Orchestrator graph checkpoint/resume | Active until workflow completion |

---

## 6. Redis Configuration

| Parameter | Value | Notes |
|-----------|-------|-------|
| Instance | Compute Engine VM (P-17) | Redis 7.2+, same region as GKE (`asia-southeast1`) |
| Persistence | RDB + AOF | Durability for checkpoints across pod restarts |
| Max memory policy | `allkeys-lru` | Evict least-recently-used keys under memory pressure |
| Access | Private IP only | Only accessible from within the GKE cluster |