# AssessorFlow — Pub/Sub Message Schema Design

> Defines all Google Cloud Pub/Sub topics, subscriptions, message payloads, and DLQ/retry policies.
> Derived from `overall.md` (end-to-end workflow), `ARCHITECTURE.md` (communication view), and implemented Terraform.
> All inter-agent communication routes through the **Orchestrator** (hub-and-spoke, Invariant #2).

---

## 1. Naming Convention

```
assessorflow.{domain}.{action}.{entity}
assessorflow.{domain}.{action}.{entity}.dlq        (dead-letter topic)
assessorflow.{domain}.{action}.{entity}.sub         (subscription)
assessorflow.{domain}.{action}.{entity}.dlq.sub     (DLQ subscription)
```

Examples:
- `assessorflow.workflow.start` — workflow start event
- `assessorflow.workflow.start.dlq` — dead-letter topic for failed messages
- `assessorflow.workflow.start.sub` — subscription
- `assessorflow.workflow.start.dlq.sub` — DLQ subscription for monitoring/replay

---

## 2. Workflow Event Topics

These topics carry workflow events between the Orchestrator and agents. Every topic has a paired DLQ topic, subscription, and DLQ subscription.

### Topic Inventory

| # | Topic | Publisher | Subscriber | Phase | Payload Description |
|---|-------|-----------|------------|-------|-------------------|
| 1 | `assessorflow.workflow.start` | Assessment Submission Service | Orchestrator | 2→3 | Assessor clicked "Start Workflow" after uploading materials; triggers material validation then agent pipeline |
| 2 | `assessorflow.validation.trigger` | Orchestrator | Validator Agent | 3, 5 | Route to Validator Agent for material validation (Phase 3) or web research content validation (Phase 5) |
| 3 | `assessorflow.validation.complete` | Validator Agent | Orchestrator | 3, 5 | Validation done — includes Terminal Signal (`PROCEED`/`TERMINATE`), extracted text passed to Knowledge Service if `PROCEED` |
| 4 | `assessorflow.classification.trigger` | Orchestrator | Classification Agent | 4 | Route to Classification Agent for sufficiency check + topic extraction |
| 5 | `assessorflow.classification.complete` | Classification Agent | Orchestrator | 6 | Topic extraction done; topics stored in Knowledge Service |
| 6 | `assessorflow.classification.insufficient` | Classification Agent | Orchestrator | 4 | Material insufficient — assessor must resubmit or trigger web search |
| 7 | `assessorflow.web-research.trigger` | Orchestrator | Web Research Agent | 5 | Route to Web Research Agent for supplementary material |
| 8 | `assessorflow.web-research.complete` | Web Research Agent | Orchestrator | 5 | Web research done; content stored in Cloud Storage and registered in `assessment_materials` |
| 9 | `assessorflow.qa-generation.trigger` | Orchestrator | Q&A Generation Agent | 7 | Route to Q&A Generation Agent |
| 10 | `assessorflow.qa-generation.complete` | Q&A Generation Agent | Orchestrator | 7 | Questions + model answers generated and stored in Assessment Submission Service |
| 11 | `assessorflow.quality-validation.complete` | Evaluator Agent | Orchestrator | 7a | Q&A quality validation passed |
| 12 | `assessorflow.quality-validation.failed` | Evaluator Agent | Orchestrator | 7a | Q&A quality validation failed — Orchestrator triggers regeneration |
| 13 | `assessorflow.human-review.approved` | Assessment Submission Service | Orchestrator | 8 | Assessor approved the question set → Orchestrator transitions to `ready_for_distribution` (does NOT auto-send invitations) |
| 14 | `assessorflow.invitation.sent` | Assessment Submission Service | Orchestrator | 9 | Assessor selected participants and sent invitations → Orchestrator transitions to `assessment_active` and publishes assessment link emails |
| 15 | `assessorflow.participant.submission-completed` | Assessment Submission Service | Orchestrator | 9 | A participant completed their assessment |
| 16 | `assessorflow.evaluation.trigger` | Orchestrator | Evaluator Agent | 10 | Route to Evaluator Agent for participant grading |
| 17 | `assessorflow.evaluation.complete` | Evaluator Agent | Orchestrator | 10 | Participant evaluation complete |
| 18 | `assessorflow.reporting.trigger` | Orchestrator | Reporting Agent | 11 | Route to Reporting Agent for report generation |
| 19 | `assessorflow.reporting.complete` | Reporting Agent | Orchestrator | 11 | Participant report generated |
| 20 | `assessorflow.workflow.complete` | Orchestrator | Email Service | 12 | All reports done; workflow finished |

> **Phase 3 validation**: Orchestrator triggers Validator Agent via Pub/Sub (#2). Validator Agent performs MRC (blur/readability via Vertex AI), OCR (text extraction via Vertex AI Vision), and content safety check (LLM reasoning). If `PROCEED`, Validator Agent passes extracted text to Knowledge Service for chunking/embedding, then publishes completion (#3). If `TERMINATE`, Orchestrator terminates the workflow.
>
> **Phase 5 web research validation**: After Web Research Agent completes (#8), Orchestrator triggers Validator Agent (#2) with the web research text for content safety validation. Same Terminal Signal response. If `PROCEED`, Validator Agent passes text to Knowledge Service (enriched_chunks), then publishes completion (#3).
>
> **Phase 7a feedback loop**: All communication between Orchestrator and agents uses Pub/Sub (hub-and-spoke). The Orchestrator triggers the Evaluator Agent via Pub/Sub, receives the validation result via Pub/Sub, and if rejected, re-triggers Q&A Generation Agent via Pub/Sub. The Orchestrator checkpoints state between each step.

---

## 3. Email Service Topics (Implemented in Terraform)

These topics are already deployed via Terraform. They use a **two-stage pattern**: `request` (trigger email preparation) and `deliver` (trigger actual send).

### 3.1 Assessor Review Email

Sends the assessor a link to review generated questions (Phase 8).

| Topic | Publisher | Subscriber |
|-------|-----------|------------|
| `assessorflow.email.request.assessor-review` | Orchestrator | Email Service |
| `assessorflow.email.deliver.assessor-review` | Email Service | Email Service |
| `assessorflow.email.request.assessor-review.dlq` | Pub/Sub (auto) | DLQ monitor |
| `assessorflow.email.deliver.assessor-review.dlq` | Pub/Sub (auto) | DLQ monitor |

### 3.2 Assessment Link Email

Sends participants the assessment URL (Phase 9).

| Topic | Publisher | Subscriber |
|-------|-----------|------------|
| `assessorflow.email.request.assessment-link` | Orchestrator | Email Service |
| `assessorflow.email.deliver.assessment-link` | Email Service | Email Service |
| `assessorflow.email.request.assessment-link.dlq` | Pub/Sub (auto) | DLQ monitor |
| `assessorflow.email.deliver.assessment-link.dlq` | Pub/Sub (auto) | DLQ monitor |

### 3.3 Participant Report Email

Sends participants their results/feedback (Phase 12).

| Topic | Publisher | Subscriber |
|-------|-----------|------------|
| `assessorflow.email.request.participant-report` | Orchestrator | Email Service |
| `assessorflow.email.deliver.participant-report` | Email Service | Email Service |
| `assessorflow.email.request.participant-report.dlq` | Pub/Sub (auto) | DLQ monitor |
| `assessorflow.email.deliver.participant-report.dlq` | Pub/Sub (auto) | DLQ monitor |

---

## 4. DLQ & Retry Policy

Every primary topic has a paired DLQ topic and subscription. This is non-negotiable.

### Pattern

```
Primary topic
  └── Primary subscription (with retry + DLQ policy)
        ├── retry_policy: exponential backoff
        └── dead_letter_policy → DLQ topic
                                    └── DLQ subscription (no retry, no further DLQ)
```

### Default Retry Configuration

| Parameter | Request Topics | Deliver Topics | Notes |
|-----------|---------------|----------------|-------|
| max_delivery_attempts | Configurable (Terraform var) | Configurable (Terraform var) | Number of retries before DLQ |
| retry_min_backoff | Configurable | Configurable | Minimum wait between retries |
| retry_max_backoff | Configurable | Configurable | Maximum wait (exponential cap) |
| ack_deadline_seconds | Shared across all subs | Shared across all subs | Time before Pub/Sub redelivers |
| message_retention_duration | Shared across all subs | Shared across all subs | How long unacked messages persist |

> DLQ subscriptions have `enable_dlq = false` — messages that land in DLQ are not retried further. They are consumed by a DLQ monitor for alerting and manual replay.

### IAM for DLQ

- Pub/Sub service agent gets `roles/pubsub.publisher` on all DLQ topics (auto-forward failed messages)
- Pub/Sub service agent gets `roles/pubsub.subscriber` on all DLQ-enabled subscriptions (permission to acknowledge and forward)

---

## 5. Message Payload Schemas

All messages use JSON. Every payload includes a standard envelope.

### 5.1 Standard Envelope

> Every Pub/Sub message in AssessorFlow wraps its data in this standard envelope. The envelope provides traceability (who sent it, when, which workflow) while the `payload` field carries the event-specific data. All services — whether publishing or subscribing — must produce and consume this exact structure. This is also the same structure recorded in the `workflow_events` table (schema.md Section 5) for audit purposes.

```json
{
  "event_id": "evt_abc123",
  "event_type": "assessorflow.workflow.start",
  "workflow_id": "wf_9f3a21",
  "timestamp": "2026-03-21T10:30:00Z",
  "source_agent": "assessment-submission-service",
  "correlation_id": "corr_xyz789",
  "payload": { ... }
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| event_id | string | Yes | Unique event identifier (UUID or prefixed). Generated by the publisher. Used for deduplication — if a subscriber receives the same `event_id` twice (due to Pub/Sub at-least-once delivery), it should ignore the duplicate. |
| event_type | string | Yes | Matches the Pub/Sub topic name (e.g. `assessorflow.workflow.start`). Subscribers use this to determine how to parse the `payload`. |
| workflow_id | string | Yes | Originates from Orchestrator `workflows.id`. Links this event to a specific assessment workflow. Every service receiving this event uses it to look up workflow context. |
| timestamp | string (ISO 8601) | Yes | When the event was published. UTC timezone. Used for ordering and debugging — not for business logic (Pub/Sub does not guarantee ordering). |
| source_agent | string | Yes | The service/agent that published this event (e.g. `assessment-submission-service`, `classification-agent`, `orchestrator-agent`). Used for audit trail and debugging. |
| correlation_id | string | Yes | End-to-end trace correlation ID. Stays the same across the entire workflow lifecycle — from the first `workflow.start` event through to `workflow.complete`. Enables distributed tracing across all services. |
| payload | object | Yes | Event-specific data. Structure varies per `event_type` — see sections 5.2–5.25 below for each payload schema. |

### 5.2 Workflow Start (Topic #1)

Published by Assessment Submission Service when the assessor clicks "Start Workflow" (Phase 2 → Phase 3). This is the first event in every workflow — it kicks off the entire assessment lifecycle. The Orchestrator receives this, creates the `workflows` record, and triggers the Validator Agent (Topic #2) for material validation. If all materials receive `PROCEED`, routes to the Classification Agent (Phase 4). If any material receives `TERMINATE`, the workflow is terminated.

```json
{
  "payload": {
    "assessment_id": "uuid",
    "assessor_id": "uuid",
    "purpose": "topic_revision",
    "difficulty_level": "easy",
    "structured_question_count": 6,
    "non_structured_question_count": 2,
    "material_ids": ["uuid1", "uuid2"],
    "participant_count": 4,
    "has_groups": true
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` — the assessment this workflow is for |
| assessor_id | string (UUID) | The assessor who created this assessment. References `users.id` in Identity Service |
| purpose | string | Fixed dropdown value selected by assessor (e.g. `topic_revision`). Passed through so downstream agents don't need to call back to Assessment Submission Service for it |
| difficulty_level | string | `easy`, `medium`, or `hard`. Used by Q&A Generation Agent to calibrate question complexity |
| structured_question_count | integer | Number of MCQ questions to generate. Determined by assessor at assessment creation |
| non_structured_question_count | integer | Number of open-ended questions to generate. If > 0, groups must be defined |
| material_ids | array of strings (UUIDs) | References `assessment_materials.id`. Each UUID is auto-generated when the assessor uploads a file (Phase 2). After all files pass validation (Phase 3), Assessment Submission Service collects the IDs of all materials for this assessment and includes them here — so the Classification Agent knows which files to retrieve from object storage without calling back to Assessment Submission Service |
| participant_count | integer | Total participants invited. Used by Orchestrator for completeness checks (evaluation + reporting) |
| has_groups | boolean | Whether participant groups are defined. If `true`, non-structured questions will be evaluated at group level |

### 5.3 Validation Trigger (Topic #2)

Published by Orchestrator to Validator Agent. Used in two phases: Phase 3 (material validation after assessor clicks "Start Workflow") and Phase 5 (web research content validation after Web Research Agent completes). Both phases use the **same payload** — the Validator Agent calls `GetMaterials` to get file metadata and downloads files from Cloud Storage. In Phase 5, the Web Research Agent has already registered its collected content in `assessment_materials` (source = `web_research`) before this trigger fires.

```json
{
  "payload": {
    "assessment_id": "uuid",
    "validation_type": "material_validation"
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id`. The Validator Agent uses this to call `GetMaterials` (gRPC 2.2.2b) to retrieve material metadata and storage paths |
| validation_type | string | `material_validation` — same for both Phase 3 and Phase 5. The Validator Agent validates all unvalidated materials (`readiness_status = NULL`) for this assessment |

**Phase 3**: Validates assessor-uploaded files (PDF/DOCX).
**Phase 5**: Validates Web Research Agent content (`.md` files + images) — already stored in `assessment_materials` with `source = 'web_research'`. Same validation path as Phase 3.

### 5.4 Validation Complete (Topic #3)

Published by Validator Agent after validating materials (Phase 3) or web research content (Phase 5). Includes the Terminal Signal (`PROCEED`/`TERMINATE`) per material. If `PROCEED`, the Validator Agent has already extracted text via OCR and passed it to Knowledge Service for chunking and embedding. The Orchestrator checks the results and decides: proceed to next phase, or terminate the workflow.

```json
{
  "payload": {
    "assessment_id": "uuid",
    "validation_type": "material_validation",
    "results": [
      {
        "material_id": "uuid",
        "terminal_signal": {
          "status": "PROCEED",
          "reason_code": "VALIDATION_PASSED",
          "message": "Document is readable. Text extracted and stored in Knowledge Service."
        }
      },
      {
        "material_id": "uuid",
        "terminal_signal": {
          "status": "TERMINATE",
          "reason_code": "HARMFUL_CONTENT",
          "message": "Document contains content that violates content safety policy."
        }
      }
    ],
    "all_proceed": false
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` |
| validation_type | string | `material_validation` or `web_research_validation` — matches the trigger |
| results | array | Per-material validation result with Terminal Signal |
| results[].material_id | string (UUID) | References `assessment_materials.id`. For web research validation, this is `null` |
| results[].terminal_signal | object | `status` (PROCEED/TERMINATE), `reason_code` (slug), `message` (human-readable) |
| all_proceed | boolean | `true` if all materials received `PROCEED`. Orchestrator uses this for quick decision |

### 5.5 Classification Trigger (Topic #4)

Published by Orchestrator to Classification Agent. This event is sent twice during a workflow: first for sufficiency check (Phase 4) and again for topic extraction (Phase 6). The `action` field tells the Classification Agent which task to perform.

```json
{
  "payload": {
    "assessment_id": "uuid",
    "material_ids": ["uuid1", "uuid2"],
    "action": "sufficiency_check"
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` |
| material_ids | array of strings (UUIDs) | References `assessment_materials.id` — the files to process. May include web research materials if Phase 5 was triggered |
| action | string | `sufficiency_check` (Phase 4 — is there enough material?) or `topic_extraction` (Phase 6 — extract main topic + subtopics and store in Knowledge Service `topics` table) |

### 5.6 Classification Complete (Topic #5)

Published by Classification Agent after topic extraction (Phase 6). At this point, the Classification Agent has already stored the main topic + subtopics in the Knowledge Service `topics` table (via gRPC). This event tells the Orchestrator that classification is done and the workflow can proceed to Q&A generation.

```json
{
  "payload": {
    "assessment_id": "uuid",
    "main_topic": "Object-Oriented Programming",
    "subtopic_count": 8,
    "topics_stored": true
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` |
| main_topic | string | The primary topic identified from the uploaded materials. Stored in `topics` table with `parent_id = NULL` |
| subtopic_count | integer | Number of subtopics extracted (e.g. 8 subtopics under OOP). The Q&A Generation Agent uses this to plan question allocation across subtopics |
| topics_stored | boolean | Confirms that topics have been successfully written to Knowledge Service `topics` table. If `false`, Orchestrator should handle the error |

### 5.7 Classification Insufficient (Topic #6)

Published by Classification Agent when the uploaded material is not sufficient for topic extraction and question generation (Phase 4). The Orchestrator receives this and presents two options to the assessor: resubmit materials or trigger web search for supplementary content.

```json
{
  "payload": {
    "assessment_id": "uuid",
    "reason": "Insufficient coverage for 8 subtopics — only 3 identifiable from current materials",
    "suggested_action": "web_search_or_resubmit"
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` |
| reason | string | Human-readable explanation of why the material is insufficient. Shown to the assessor so they understand the gap |
| suggested_action | string | `web_search_or_resubmit` — the two options available. The assessor chooses via the frontend, and the Orchestrator routes accordingly (resubmit → restart from Phase 2, web search → Phase 5) |

### 5.8 Web Research Trigger (Topic #7)

Published by Orchestrator to Web Research Agent when the assessor chooses web search support after material insufficiency (Phase 5). The Web Research Agent reads existing document chunks from Knowledge Service via `GetChunksByWorkflow` (gRPC) to understand what's already covered, then searches the web for supplementary content that fills gaps.

```json
{
  "payload": {
    "assessment_id": "uuid",
    "workflow_id": "wf_9f3a21",
    "main_topic": "Object-Oriented Programming"
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` |
| workflow_id | string | The Web Research Agent uses this to call Knowledge Service `GetChunksByWorkflow` (3.2.3) — reads existing document chunks to understand what content is already covered and avoid duplication |
| main_topic | string | The assessor-provided topic for the web search. The Web Research Agent uses this as the search query basis |

### 5.9 Web Research Complete (Topic #8)

Published by Web Research Agent after completing web search and registering collected content (Phase 5). The agent collects text and image results from the web, stores them in Cloud Object Storage, and registers them in `assessment_materials` (source = `web_research`) via `UploadWebResearchMaterials` (gRPC 2.2.6) **before** publishing this event. The Orchestrator receives this and triggers the Validator Agent to validate the new materials — same path as Phase 3.

```json
{
  "payload": {
    "assessment_id": "uuid",
    "workflow_id": "wf_9f3a21",
    "materials_registered": 3,
    "material_ids": ["uuid-1", "uuid-2", "uuid-3"],
    "sources_found": 3
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` |
| workflow_id | string | Originates from Orchestrator `workflows.id` |
| materials_registered | integer | Number of materials registered in `assessment_materials` via gRPC |
| material_ids | array of strings (UUIDs) | References `assessment_materials.id` — the new rows created with `source = 'web_research'`. Validator Agent will validate these |
| sources_found | integer | Number of web sources that contributed to the collected content |

### 5.10 Q&A Generation Trigger (Topic #9)

Published by Orchestrator to Q&A Generation Agent after classification is complete (Phase 7). The Q&A Generation Agent uses the classified topics from Knowledge Service and retrieves document chunks via similarity search to generate questions + model answers. The `question_set_id` is pre-generated by the Orchestrator so that all parties (Q&A Gen, Evaluator, Orchestrator) can reference the same set.

```json
{
  "payload": {
    "assessment_id": "uuid",
    "question_set_id": "uuid",
    "structured_count": 6,
    "non_structured_count": 2,
    "difficulty_level": "easy",
    "purpose": "topic_revision"
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` |
| question_set_id | string (UUID) | Pre-generated by Orchestrator. Becomes the PK of `question_sets` in Assessment Submission Service. All downstream references (Evaluator, approved questions) use this ID |
| structured_count | integer | Number of MCQ questions to generate |
| non_structured_count | integer | Number of open-ended questions to generate |
| difficulty_level | string | `easy`, `medium`, or `hard`. Guides the Q&A Generation Agent's prompt to calibrate question complexity |
| purpose | string | Assessment purpose (e.g. `topic_revision`). Provides context to the Q&A Generation Agent for generating contextually appropriate questions |

### 5.11 Q&A Generation Complete (Topic #10)

Published by Q&A Generation Agent after generating questions + model answers and storing them in Assessment Submission Service (Phase 7). The Orchestrator receives this and triggers the Evaluator Agent for quality validation (Phase 7a). If the Evaluator rejects the Q&A, the Orchestrator re-triggers Q&A generation — the `iteration` field tracks how many times this has happened.

```json
{
  "payload": {
    "assessment_id": "uuid",
    "question_set_id": "uuid",
    "structured_generated": 6,
    "non_structured_generated": 2,
    "iteration": 1
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` |
| question_set_id | string (UUID) | References `question_sets.id` in Assessment Submission Service |
| structured_generated | integer | Number of MCQ questions actually generated. Should match the requested count |
| non_structured_generated | integer | Number of open-ended questions actually generated |
| iteration | integer | Feedback loop iteration count. Starts at 1. Increments each time the Evaluator rejects and Q&A Gen regenerates. Matches `question_sets.iteration_count` |

### 5.12 Quality Validation Complete (Topic #11)

Published by Evaluator Agent after Q&A passes quality validation (Phase 7a). The Orchestrator receives this via Pub/Sub, updates `question_sets.status` to `validated`, and proceeds to human review (Phase 8).

```json
{
  "payload": {
    "assessment_id": "uuid",
    "question_set_id": "uuid",
    "validation_result": "pass",
    "iteration": 1
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` |
| question_set_id | string (UUID) | References `question_sets.id` — the validated question set |
| validation_result | string | Always `pass` for this topic. Confirms all questions are grounded, model answers are accurate, and difficulty matches the config |
| iteration | integer | Which feedback loop iteration produced the final approved set |

### 5.13 Quality Validation Failed (Topic #12)

Published by Evaluator Agent when Q&A fails quality validation (Phase 7a). The Orchestrator receives this and re-triggers the Q&A Generation Agent with the feedback, so it knows what to fix in the next iteration. The `feedback.issues` array provides specific, actionable reasons for rejection.

```json
{
  "payload": {
    "assessment_id": "uuid",
    "question_set_id": "uuid",
    "validation_result": "fail",
    "iteration": 1,
    "feedback": {
      "issues": ["Q3 not grounded in source material", "Q5 model answer incomplete"],
      "action": "regenerate"
    }
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` |
| question_set_id | string (UUID) | References `question_sets.id` — the rejected question set |
| validation_result | string | Always `fail` for this topic |
| iteration | integer | Which iteration was rejected. The next regeneration will be `iteration + 1` |
| feedback | object | Structured feedback for the Q&A Generation Agent |
| feedback.issues | array of strings | Specific problems found — e.g. questions not grounded in source material, model answers incomplete or inaccurate, difficulty mismatch |
| feedback.action | string | Always `regenerate` — tells the Q&A Generation Agent to produce a new set addressing the listed issues |

### 5.14 Human Review Approved (Topic #13)

Published by Assessment Submission Service after the assessor reviews and approves the question set (Phase 8). This is the single HITL gate in the workflow. The assessor may remove questions they don't like — the approved subset is copied from `generated_questions` to `approved_questions` (both tables are within Assessment Submission Service — no cross-service copy). Removed questions are marked `was_approved = false` in `generated_questions` for audit trail. The Orchestrator receives this and transitions the workflow to `ready_for_distribution` — the assessment is approved but invitations have not been sent yet. The assessor must explicitly send invitations (Topic #14).

```json
{
  "payload": {
    "assessment_id": "uuid",
    "approved_question_set_id": "uuid",
    "questions_approved": 7,
    "questions_removed": 1
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` |
| approved_question_set_id | string (UUID) | References `approved_question_sets.id` — the final approved set. Links back to `question_sets` via `original_question_set_id` for audit |
| questions_approved | integer | Number of questions the assessor kept. Copied to `approved_questions` |
| questions_removed | integer | Number of questions the assessor removed. Remain in `generated_questions` with `was_approved = false` for audit trail. Both tables are in Assessment Submission Service |

### 5.15 Invitation Sent (Topic #14)

Published by Assessment Submission Service when the assessor selects participants and sends invitations via `POST /api/v1/assessments/{id}/invite` (2.4.5). The Orchestrator receives this, transitions the workflow to `assessment_active`, and publishes `assessorflow.email.request.assessment-link` per participant to Email Service.

```json
{
  "payload": {
    "assessment_id": "uuid",
    "participant_ids": ["uuid-1", "uuid-2", "uuid-3", "uuid-4"],
    "invitations_sent": 4
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` |
| participant_ids | array of strings (UUIDs) | References `assessment_participants.id` — the participants who were invited |
| invitations_sent | integer | Number of invitations sent |

### 5.16 Participant Submission Completed (Topic #15)

Published by Assessment Submission Service when a participant completes and submits their assessment (Phase 9). One event per participant. The Orchestrator receives this and decides whether to trigger evaluation immediately or wait — for non-structured (group) questions, it waits until all group members have submitted before triggering group-level evaluation.

```json
{
  "payload": {
    "assessment_id": "uuid",
    "participant_id": "uuid",
    "submission_id": "uuid",
    "submitted_at": "2026-03-21T14:00:00Z"
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` |
| participant_id | string (UUID) | References `assessment_participants.id` — the participant who submitted |
| submission_id | string (UUID) | References `participant_submissions.id` — contains the participant's answers. The Evaluator Agent uses this to retrieve answers via Assessment Submission Service |
| submitted_at | string (ISO 8601) | When the participant clicked submit. Stored in `participant_submissions.submitted_at` |

### 5.17 Evaluation Trigger (Topic #16)

Published by Orchestrator to Evaluator Agent (Phase 10). The Orchestrator sends one trigger per participant. For structured questions (MCQ), the Evaluator Agent scores immediately using deterministic matching — no LLM needed. For non-structured questions, the Orchestrator only sends this trigger after all group members have submitted, and includes the `group_id` so the Evaluator Agent can evaluate the group's answers together and assign a shared score.

```json
{
  "payload": {
    "assessment_id": "uuid",
    "participant_id": "uuid",
    "submission_id": "uuid",
    "has_non_structured": true,
    "group_id": "uuid"
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` |
| participant_id | string (UUID) | References `assessment_participants.id` — which participant to evaluate |
| submission_id | string (UUID) | References `participant_submissions.id` — the Evaluator Agent retrieves answers from Assessment Submission Service using this ID |
| has_non_structured | boolean | If `true`, the assessment includes open-ended questions that require LLM-based evaluation at group level. If `false`, all questions are MCQ and scored deterministically |
| group_id | string (UUID) or null | References `participant_groups.id`. Included when `has_non_structured = true` — tells the Evaluator Agent which group this participant belongs to for shared scoring. `null` when the assessment has no non-structured questions |

### 5.18 Evaluation Complete (Topic #17)

Published by Evaluator Agent per participant (Phase 10). One event is published per participant after all their questions are scored. The Orchestrator uses this to increment `evaluations_completed` and perform the completeness check — once `evaluations_completed == total_participants`, the workflow proceeds to reporting (Phase 11). Note: the Evaluator Agent only scores here — it does **not** generate feedback. Feedback is the Reporting Agent's responsibility.

```json
{
  "payload": {
    "assessment_id": "uuid",
    "participant_id": "uuid",
    "evaluation_id": "uuid",
    "total_score": 85.5,
    "max_score": 100.0,
    "evaluation_method": {
      "structured": "deterministic",
      "non_structured": "llm_based"
    }
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` |
| participant_id | string (UUID) | References `assessment_participants.id` — which participant was evaluated |
| evaluation_id | string (UUID) | References `evaluations.id` — the evaluation record containing per-question score breakdowns in `evaluation_details` |
| total_score | decimal | Participant's total score across all questions (structured + non-structured) |
| max_score | decimal | Maximum possible score for the assessment |
| evaluation_method | object | Shows which scoring method was used per question type. `deterministic` = MCQ answer compared against pre-validated correct answer (no LLM). `llm_based` = open-ended answer evaluated by LLM using model answer + source chunks as grounding. If the assessment has no non-structured questions, `non_structured` key is omitted |

### 5.19 Reporting Trigger (Topic #18)

Published by Orchestrator to Reporting Agent after all evaluations are complete (Phase 11). One trigger per participant. The Orchestrator enforces a maximum number of concurrent reporting instances to control infrastructure cost — it does not fire all triggers at once. The Reporting Agent reads everything it needs from **Assessment Submission Service** (single source) — questions, answers, and scores are all there. The Reporting Agent then **generates feedback itself** — both per-question feedback and overall summary feedback. The Evaluator Agent only provides scores, not feedback (see overall.md Phase 11, schema.md Section 2).

```json
{
  "payload": {
    "assessment_id": "uuid",
    "participant_id": "uuid",
    "evaluation_id": "uuid"
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` |
| participant_id | string (UUID) | References `assessment_participants.id` — which participant's report to generate |
| evaluation_id | string (UUID) | References `evaluations.id` in Assessment Submission Service. The Reporting Agent uses this to retrieve scores and per-question breakdowns from `evaluation_details`. Combined with questions + participant answers (also in Assessment Submission Service), the Reporting Agent generates per-question feedback and overall summary feedback |

### 5.20 Reporting Complete (Topic #19)

Published by Reporting Agent after generating a participant's report (Phase 11). The Orchestrator uses this to increment `reports_completed` and perform the completeness check — once `reports_completed == total_participants`, the workflow proceeds to completion (Phase 12) and triggers participant report emails.

```json
{
  "payload": {
    "assessment_id": "uuid",
    "participant_id": "uuid",
    "report_id": "uuid"
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` |
| participant_id | string (UUID) | References `assessment_participants.id` — which participant's report was generated |
| report_id | string (UUID) | References `participant_reports.id` — the generated report containing per-question feedback + overall summary in `report_content` (JSONB) |

### 5.21 Workflow Complete (Topic #20)

Published by Orchestrator after all participant reports are generated and `reports_completed == total_participants` (Phase 12). This is the final event in the workflow lifecycle. The Email Service subscribes to this topic and triggers participant report emails. The Orchestrator marks the workflow as `completed` and the Redis hot state is no longer refreshed.

```json
{
  "payload": {
    "assessment_id": "uuid",
    "total_participants": 4,
    "total_reports": 4,
    "completed_at": "2026-03-21T16:00:00Z"
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` |
| total_participants | integer | Total number of participants in this assessment |
| total_reports | integer | Should equal `total_participants`. Confirms all reports were generated |
| completed_at | string (ISO 8601) | When the workflow reached completion. Stored in `workflows.updated_at` |

### 5.22 Email Request — Assessor Review

Published by Orchestrator to Email Service after Q&A passes quality validation (Phase 7a → Phase 8). The Email Service prepares and sends the assessor an email with a link to review the generated questions. This is the first stage of the two-stage email pattern (`request` → `deliver`).

```json
{
  "payload": {
    "assessment_id": "uuid",
    "assessor_email": "assessor@email.com",
    "review_link": "https://app.assessorflow.com/review/uuid",
    "question_count": 8
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` |
| assessor_email | string | The assessor's email address. Retrieved from `users.email` via `assessment_configs.assessor_id` |
| review_link | string (URL) | Deep link to the frontend question review page. The assessor clicks this to view, remove, and approve questions |
| question_count | integer | Total questions available for review. Helps the assessor gauge the review effort before clicking |

### 5.23 Email Request — Assessment Link

Published by Orchestrator to Email Service after the assessor approves the question set (Phase 8 → Phase 9). One event per participant. The Email Service sends each participant an invitation email with a link to take the assessment. Participants are not registered users — this email is their entry point into the system.

```json
{
  "payload": {
    "assessment_id": "uuid",
    "participant_email": "student1@email.com",
    "assessment_link": "https://app.assessorflow.com/assessment/uuid",
    "duration_minutes": 60,
    "deadline": "2026-03-25T23:59:00Z"
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` |
| participant_email | string | From `assessment_participants.email`. The invitation recipient |
| assessment_link | string (URL) | Deep link to the assessment page. When the participant clicks this, a `participant:{email}` session is created in Redis (see `redis_store.md` Section 2) |
| duration_minutes | integer | Time limit for the assessment. From `assessment_configs.duration_minutes`. Shown in the email so participants can plan |
| deadline | string (ISO 8601) | Last date/time to take the assessment. After this, the link expires |

### 5.24 Email Request — Participant Report

Published by Orchestrator to Email Service after workflow completion (Phase 12). One event per participant. The Email Service sends each participant an email with a link to view their results — including per-question feedback and overall summary feedback generated by the Reporting Agent.

```json
{
  "payload": {
    "assessment_id": "uuid",
    "participant_email": "student1@email.com",
    "report_id": "uuid",
    "report_link": "https://app.assessorflow.com/report/uuid"
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` |
| participant_email | string | From `assessment_participants.email`. The report recipient |
| report_id | string (UUID) | References `participant_reports.id` — the generated report containing per-question feedback + overall summary in `report_content` (JSONB) |
| report_link | string (URL) | Deep link to the frontend report page where the participant can view their scores and feedback |

### 5.25 Email Deliver (All 3 flows)

Published by Email Service to itself after preparing the email content. This is the second stage of the two-stage email pattern. The `request` stage (5.19–5.21) triggers email preparation — the Email Service renders the template, creates an `email_log` record with status `queued`, and publishes a `deliver` event. The `deliver` stage then performs the actual SMTP send. This separation ensures that email rendering failures and send failures are retried independently with their own DLQ policies.

```json
{
  "payload": {
    "email_log_id": "uuid",
    "recipient_email": "recipient@email.com",
    "subject": "Your AssessorFlow Report",
    "template_id": "participant_report_v1",
    "rendered_html": "...",
    "rendered_text": "..."
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| email_log_id | string (UUID) | References `email_log.id` — the Email Service updates this record's `status` to `sent` or `failed` after delivery |
| recipient_email | string | The email address to send to |
| subject | string | Rendered email subject line |
| template_id | string | Which email template was used (e.g. `assessor_review_v1`, `assessment_link_v1`, `participant_report_v1`). Enables template versioning |
| rendered_html | string | The fully rendered HTML email body, ready to send |
| rendered_text | string | Plain-text fallback for email clients that don't support HTML |

---

## 6. Topic-to-Phase Mapping

| Phase | Event Flow | Topics Used |
|-------|-----------|-------------|
| 2→3 | Assessor clicks "Start Workflow" → material validation begins | #1 (workflow.start) |
| 3 | Orchestrator → Validator Agent (material validation + OCR + content safety) | #2 (validation.trigger), #3 (validation.complete) |
| 4 | Orchestrator → Classification Agent (sufficiency check) | #4 (classification.trigger) |
| 4 | Material insufficient | #6 (classification.insufficient) |
| 5 | Orchestrator → Web Research | #7 (web-research.trigger), #8 (web-research.complete) |
| 5 | Web research content → Validator Agent (content safety) | #2 (validation.trigger), #3 (validation.complete) |
| 6 | Classification done (topic extraction) | #5 (classification.complete) |
| 7 | Orchestrator → Q&A Gen | #9 (qa-generation.trigger), #10 (qa-generation.complete) |
| 7a | Quality validation result | #11 (quality-validation.complete), #12 (quality-validation.failed) |
| 8 | Assessor approves → `ready_for_distribution` | #13 (human-review.approved), email.request.assessor-review |
| 8→9 | Assessor sends invitations → `assessment_active` | #14 (invitation.sent), email.request.assessment-link |
| 9 | Participant submits | #15 (participant.submission-completed) |
| 10 | Orchestrator → Evaluation | #16 (evaluation.trigger), #17 (evaluation.complete) |
| 11 | Orchestrator → Reporting | #18 (reporting.trigger), #19 (reporting.complete) |
| 12 | Workflow done + report email | #20 (workflow.complete), email.request.participant-report |
