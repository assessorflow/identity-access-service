# AssessorFlow — Database Schema Design

> Derived from `References/overall.md` (end-to-end workflow, data model concepts, service ownership).
> Audited against `References/SCHEMA_AUDIT_REPORT.md`, `References/SCHEMA_AUDIT_REPORT_2.md`, and `References/ARCHITECTURE.md`.
> Single PostgreSQL 18+ instance (Cloud SQL) + pgvector 0.8+. **Database-per-service** — each service owns its own logical database on a single Cloud SQL instance. Databases are logically separated but physically co-located.
> **Runtime agents (Classification, Q&A Generation, Evaluation, Web Research, Reporting) do not own database tables.** All persistent data is owned by backend services (Assessment Submission, Knowledge, Identity, Email) or shared infrastructure (Orchestrator, Decision Audit).

---

## 1. Identity and Access Service

### `users`

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| email | VARCHAR(255) | UNIQUE, NOT NULL | Login identifier |
| password_hash | VARCHAR(255) | NOT NULL | Hashed password |
| full_name | VARCHAR(255) | NOT NULL | |
| role | VARCHAR(50) | NOT NULL | `assessor`, `admin` |
| is_active | BOOLEAN | NOT NULL, DEFAULT true | |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |
| updated_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

### `sessions`

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| user_id | UUID | FK → users.id, NOT NULL | |
| refresh_token | VARCHAR(512) | UNIQUE, NOT NULL | Refresh token — used to issue new access tokens (JWT). Access tokens are stateless and not stored. |
| expires_at | TIMESTAMPTZ | NOT NULL | |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

> **Redis cache**: `user:{user_id}` → `{ user_id, workflow_id, role }` for distributed access (overall.md Phase 1). No permissions — access control is role-based only. See `redis_store.md` for full Redis key design.

---

## 2. Assessment Submission Service

> The central record keeper for the entire assessment lifecycle. Owns all persistent data that agents read from and write to — assessment config, materials, generated questions (drafts + approved), participant submissions, evaluation scores, and reports. Agents are pure compute — they process data and write results back here.

### `assessment_configs`

> The central configuration record for each assessment workflow. Stores everything the assessor defines at creation time — purpose, difficulty, duration, question counts — and tracks the lifecycle status. All other tables in this service FK back to `assessment_configs.id`. Corresponds to the assessment config payload in overall.md Phase 2 (step 5).

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| workflow_id | VARCHAR(50) | UNIQUE, DEFAULT NULL | Originates from Orchestrator `workflows.id`. NULL at creation (Phase 2) — set when Orchestrator creates the workflow after material validation passes (Phase 3→4) |
| assessor_id | UUID | NOT NULL | References user in Identity Service |
| assessment_title | VARCHAR(255) | NOT NULL | Assessor-defined title for this assessment (e.g. "OOP Mid-Term Quiz") |
| purpose | VARCHAR(100) | NOT NULL | Fixed dropdown value (e.g. `topic_revision`) |
| duration_minutes | INTEGER | NOT NULL | e.g. 60 |
| difficulty_level | VARCHAR(50) | NOT NULL | `easy`, `medium`, `hard` |
| structured_question_count | INTEGER | NOT NULL, DEFAULT 0 | MCQ count |
| non_structured_question_count | INTEGER | NOT NULL, DEFAULT 0 | Open-ended count |
| deadline | TIMESTAMPTZ | DEFAULT NULL | Last date/time participants can take the assessment. Set by assessor at creation. After this, assessment links expire |
| status | VARCHAR(50) | NOT NULL, DEFAULT 'draft' | `draft`, `material_validation`, `insufficient`, `processing`, `ready_for_distribution`, `assessment_active`, `evaluating`, `completed`, `terminated` |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |
| updated_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

> `insufficient` status added for Phase 4 when Classification Agent determines material is not sufficient.

### `assessment_participants`

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| assessment_id | UUID | FK → assessment_configs.id, NOT NULL | |
| email | VARCHAR(255) | NOT NULL | Participant email (not a registered user) |
| invitation_status | VARCHAR(50) | NOT NULL, DEFAULT 'pending' | `pending`, `sent`, `accepted` |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

> UNIQUE(assessment_id, email)

### `participant_groups`

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| assessment_id | UUID | FK → assessment_configs.id, NOT NULL | |
| group_name | VARCHAR(100) | NOT NULL | e.g. `Group A` |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

> UNIQUE(assessment_id, group_name)

### `participant_group_members`

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| group_id | UUID | FK → participant_groups.id, NOT NULL | |
| participant_id | UUID | FK → assessment_participants.id, NOT NULL | |

> UNIQUE(group_id, participant_id)

### `assessment_materials`

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| assessment_id | UUID | FK → assessment_configs.id, NOT NULL | |
| file_name | VARCHAR(255) | NOT NULL | Original filename |
| storage_path | VARCHAR(512) | NOT NULL | Cloud object storage path |
| file_type | VARCHAR(50) | NOT NULL | `pdf`, `docx`, `png`, `jpg` |
| readiness_status | VARCHAR(20) | DEFAULT NULL | Terminal Signal status: `PROCEED`, `TERMINATE`. NULL = not yet validated. Set by Validator Agent (Phase 3/5) |
| validation_reason_code | VARCHAR(100) | DEFAULT NULL | Reason code slug (e.g. `VALIDATION_PASSED`, `BLURRY_UNREADABLE`, `HARMFUL_CONTENT`, `PII_DETECTED`). See CR-STA-001 Q-7 for full taxonomy |
| validation_message | TEXT | DEFAULT NULL | Human-readable explanation from Validator Agent (e.g. "30% blurry but key figures legible"). Shown to assessor in UI |
| source | VARCHAR(50) | NOT NULL, DEFAULT 'upload' | `upload` (assessor-uploaded files) or `web_research` (Web Research Agent collected content) |
| source_url | VARCHAR(1024) | DEFAULT NULL | Source URL for web research content. NULL for assessor uploads |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

> The Web Research Agent acts as a replacement for manual assessor uploads — it registers collected content (text saved as `.md` files, images as `.png`/`.jpg`) in `assessment_materials` with `source = 'web_research'`. The Validator Agent then validates them through the same path as assessor uploads.

### `assessment_rubrics`

> Uploaded by the assessor in a **separate upload area** from learning materials (Phase 2). Stores marking rubric documents (PDF/DOCX) that the assessor wants the system to follow during grading. The file is stored in Cloud Object Storage. During Phase 3, the **Validator Agent** validates the rubric (readability + content safety), extracts text via OCR, and passes the extracted text to Knowledge Service for chunking, embedding, and storage in `policy_chunks` (Policy KB). During Phase 10, the Evaluator Agent searches these rubric embeddings to ground its grading decisions, providing explainability for how marks were awarded.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| assessment_id | UUID | FK → assessment_configs.id, NOT NULL | |
| file_name | VARCHAR(255) | NOT NULL | Original filename |
| storage_path | VARCHAR(512) | NOT NULL | Cloud object storage path |
| file_type | VARCHAR(50) | NOT NULL | `pdf`, `docx` |
| readiness_status | VARCHAR(20) | DEFAULT NULL | Terminal Signal status: `PROCEED`, `TERMINATE`. NULL = not yet validated. Set by Validator Agent (Phase 3) |
| validation_reason_code | VARCHAR(100) | DEFAULT NULL | Same taxonomy as `assessment_materials` |
| validation_message | TEXT | DEFAULT NULL | Human-readable explanation from Validator Agent |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

### `question_sets`

> Written by the **Q&A Generation Agent** (Phase 7). Tracks the question generation lifecycle including feedback loop iterations with the Evaluator Agent (Phase 7a). The Q&A Generation Agent creates this record, and updates `iteration_count` and `status` as the feedback loop progresses.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | = question_set_id |
| workflow_id | VARCHAR(50) | NOT NULL | Originates from Orchestrator `workflows.id` |
| iteration_count | INTEGER | NOT NULL, DEFAULT 0 | Tracks Phase 7a feedback loop cycles |
| status | VARCHAR(50) | NOT NULL, DEFAULT 'generated' | `generated`, `validating`, `validated`, `under_review`, `approved`, `rejected` |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |
| updated_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

### `generated_questions`

> Written by the **Q&A Generation Agent** (Phase 7). This is the **draft table** — all questions generated across all feedback loop iterations live here, including questions the assessor later removes. When the assessor approves (Phase 8), the kept questions are copied to `approved_questions`. This table retains ALL versions for audit trail.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| question_set_id | UUID | FK → question_sets.id, NOT NULL | |
| iteration | INTEGER | NOT NULL, DEFAULT 1 | Which feedback loop iteration produced this version |
| question_type | VARCHAR(50) | NOT NULL | `structured`, `non_structured` |
| content | TEXT | NOT NULL | Question text |
| structured_answer | CHAR(1) | | Correct option letter (e.g. `A`, `B`, `C`, `D`). NULL for non-structured questions. |
| non_structured_model_answer | TEXT | | Reference model answer for open-ended questions. NULL for structured questions. |
| metadata | JSONB | | MCQ options, source chunk_ids, difficulty, rubric |
| topic_id | UUID | | References topic used for generation |
| was_approved | BOOLEAN | DEFAULT NULL | NULL = pending, true = kept, false = removed by assessor |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

> **Data lifecycle:** Q&A Generation Agent generates → writes here → Evaluator Agent validates by reading from here (Phase 7a) → assessor reviews (Phase 8) → approved questions copied to `approved_questions`. This table retains ALL versions across ALL iterations (including rejected ones) for audit trail.

### `approved_question_sets`

> Created during Phase 8 (HITL) when the assessor approves the question set. This is the **finalized, read-optimized copy** — a clean table for participants (Phase 9) and Evaluator Agent (Phase 10) to read from, without mixing in draft/rejected questions. Only one approved set per assessment.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | Own UUID — this is NOT the same as `question_sets.id` |
| assessment_id | UUID | FK → assessment_configs.id, UNIQUE, NOT NULL | One approved set per assessment |
| original_question_set_id | UUID | FK → question_sets.id, NOT NULL | Links back to the draft `question_sets` record for audit trail |
| approved_at | TIMESTAMPTZ | NOT NULL | When the assessor approved |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

### `approved_questions`

> The **finalized questions** that survived HITL review (Phase 8). Only questions the assessor kept are copied here from `generated_questions`. This is what participants see during the assessment (Phase 9) and what the Evaluator Agent grades against (Phase 10). Questions the assessor removed remain in `generated_questions` with `was_approved = false` for audit trail — they never appear here.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| question_set_id | UUID | FK → approved_question_sets.id, NOT NULL | |
| question_type | VARCHAR(50) | NOT NULL | `structured` (MCQ), `non_structured` (open-ended) |
| content | TEXT | NOT NULL | Question text |
| structured_answer | CHAR(1) | | Correct option letter (e.g. `A`, `B`, `C`, `D`). NULL for non-structured questions. |
| non_structured_model_answer | TEXT | | Reference model answer for open-ended questions. NULL for structured questions. Used by Evaluator Agent (Phase 10) for LLM-based grounding. |
| metadata | JSONB | | Question-type-specific data (see sample below) |
| sort_order | INTEGER | NOT NULL | Display order |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

> Both `structured_answer` and `non_structured_model_answer` are generated by the **Question and Answer Generation Agent** (Phase 7) and validated by the **Evaluator Agent** (Phase 7a feedback loop) before reaching this table.

> **`metadata` column** — stores question-type-specific data that varies between structured and non-structured questions. Using JSONB because the shape differs per question type, and these fields don't need relational constraints or indexing. Relational columns would either be nullable half the time or require separate tables per question type — JSONB keeps it simple.
>
> **Where `metadata` is used:**
> - **Frontend (Phase 8 & 9)** — reads `options` to render MCQ choices for assessor review and participant assessment
> - **Evaluator Agent (Phase 10)** — reads `rubric` and `max_marks` for LLM-based grading of non-structured answers; reads `source_chunk_ids` to retrieve the same document chunks for grounded evaluation
> - **Reporting Agent (Phase 11)** — reads `topic` and `difficulty` to generate per-topic performance breakdowns in participant reports
> - **Audit/Traceability** — `source_chunk_ids` links every question back to the exact document chunks it was generated from (Invariant #7: grounding required)

**Sample `metadata` for structured (MCQ):**
```json
{
  "options": {
    "A": "Encapsulation",
    "B": "Polymorphism",
    "C": "Compilation",
    "D": "Iteration"
  },
  "option_explanations": {
    "A": { "text": "Encapsulation", "explanation": "Correct. Encapsulation bundles data with the methods that operate on it and restricts direct access.", "is_correct": true },
    "B": { "text": "Polymorphism", "explanation": "Incorrect. Polymorphism allows objects of different classes to respond to the same method call.", "is_correct": false },
    "C": { "text": "Compilation", "explanation": "Incorrect. Compilation is a code translation process, not an OOP concept.", "is_correct": false },
    "D": { "text": "Iteration", "explanation": "Incorrect. Iteration is a loop construct, not an OOP principle.", "is_correct": false }
  },
  "source_chunk_ids": ["chunk_101", "chunk_205"],
  "difficulty": "easy",
  "topic": "Object-Oriented Programming"
}
```

> **`option_explanations` (ADR-44)**: Pre-generated by the Q&A Generation Agent at Phase 7 for every MCQ question. Explains why the correct answer is right and why each distractor is wrong. Validated by the Evaluator Agent during Phase 7a quality check. Served deterministically at Phase 11/12 — no LLM call at report time.

**Sample `metadata` for non-structured (open-ended):**
```json
{
  "rubric": "Award marks for identifying at least 2 key differences between encapsulation and abstraction, with examples from the source material.",
  "max_marks": 10,
  "source_chunk_ids": ["chunk_101", "chunk_302"],
  "difficulty": "medium",
  "topic": "Object-Oriented Programming"
}
```

### `participant_submissions`

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | = user_question_set_id in overall.md |
| assessment_id | UUID | FK → assessment_configs.id, NOT NULL | |
| participant_id | UUID | FK → assessment_participants.id, NOT NULL | |
| started_at | TIMESTAMPTZ | | When participant began |
| submitted_at | TIMESTAMPTZ | | When participant submitted |
| status | VARCHAR(50) | NOT NULL, DEFAULT 'in_progress' | `in_progress`, `submitted`, `evaluated`, `reported` |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

> UNIQUE(assessment_id, participant_id)

### `participant_answers`

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | = sub_user_question_id in overall.md |
| submission_id | UUID | FK → participant_submissions.id, NOT NULL | |
| question_id | UUID | FK → approved_questions.id, NOT NULL | |
| answer_content | TEXT | | Participant's answer |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

> UNIQUE(submission_id, question_id)

### `evaluations`

> Written by the **Evaluator Agent** (Phase 10). One row per participant per assessment — the parent record holding the total score.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| workflow_id | VARCHAR(50) | NOT NULL | Originates from Orchestrator `workflows.id` |
| participant_id | UUID | NOT NULL | |
| submission_id | UUID | FK → participant_submissions.id, NOT NULL | |
| total_score | DECIMAL(5,2) | | |
| max_score | DECIMAL(5,2) | | |
| status | VARCHAR(50) | NOT NULL, DEFAULT 'pending' | `pending`, `in_progress`, `completed` |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

> The Evaluator Agent only scores — it does **not** generate feedback. Feedback is generated by the Reporting Agent (Phase 11).

### `evaluation_details`

> Written by the **Evaluator Agent** (Phase 10). One row per question per participant — breaks down the score per question.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| evaluation_id | UUID | FK → evaluations.id, NOT NULL | |
| question_id | UUID | FK → approved_questions.id, NOT NULL | |
| group_evaluation_id | UUID | FK → group_evaluations.id, NULL | NULL for structured questions; links to group evaluation for non-structured |
| score | DECIMAL(5,2) | NOT NULL | |
| max_score | DECIMAL(5,2) | NOT NULL | |
| reasoning | TEXT | | AI evaluation reasoning (transparency) — only populated for non-structured (LLM-based) |
| evaluation_method | VARCHAR(50) | NOT NULL | `deterministic` (MCQ) or `llm_based` (open-ended) |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

> No `feedback` column here — the Evaluator Agent only assigns scores. Per-question feedback is generated by the Reporting Agent and stored in `participant_reports.report_content`.

### `group_evaluations`

> Written by the **Evaluator Agent** (Phase 10). One row per non-structured question per group — the shared score source for group-based open-ended questions. All members of the same group share the same `group_evaluations` row for a given question, guaranteeing identical scores.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| workflow_id | VARCHAR(50) | NOT NULL | Originates from Orchestrator `workflows.id` |
| group_id | UUID | FK → participant_groups.id, NOT NULL | |
| question_id | UUID | FK → approved_questions.id, NOT NULL | Non-structured question |
| group_score | DECIMAL(5,2) | NOT NULL | Same score for all group members |
| max_score | DECIMAL(5,2) | NOT NULL | |
| reasoning | TEXT | | LLM evaluation reasoning |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

> UNIQUE(group_id, question_id) — ensures one group evaluation per group per question. `CreateGroupEvaluation` (2.6.2) is idempotent: if a row already exists for this combination, returns the existing record instead of creating a duplicate.

> **3-table evaluation relationship:**
>
> **Example — mixed assessment, Group A has participant X and Y:**
> ```
> group_evaluations: (Group A, Q7: 15/20)    ← one shared row
>
> evaluations: (participant X, total: 85/100)
>   └── evaluation_details: (Q1: 10/10, deterministic, group_evaluation_id: NULL)
>   └── evaluation_details: (Q7: 15/20, llm_based, group_evaluation_id: → Group A Q7)
>
> evaluations: (participant Y, total: 80/100)
>   └── evaluation_details: (Q1: 8/10, deterministic, group_evaluation_id: NULL)
>   └── evaluation_details: (Q7: 15/20, llm_based, group_evaluation_id: → Group A Q7)  ← SAME score as X
> ```

### `participant_reports`

> Written by the **Reporting Agent** (Phase 11). The Reporting Agent reads questions, answers, and scores from this same service (Assessment Submission), then generates **two levels of feedback**:
> - **Per-question feedback** — explains why the answer was correct/incorrect, referencing model answers and source material
> - **Overall summary feedback** — aggregates performance, highlights strengths, identifies improvement areas, provides personalised study recommendations
>
> Both are stored together in `report_content`.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| workflow_id | VARCHAR(50) | NOT NULL | Originates from Orchestrator `workflows.id` |
| participant_id | UUID | NOT NULL | |
| evaluation_id | UUID | FK → evaluations.id, NOT NULL | Direct link to scoring data |
| report_content | JSONB | NOT NULL | Contains per-question feedback + overall summary (see sample below) |
| status | VARCHAR(50) | NOT NULL, DEFAULT 'generating' | `generating`, `completed`, `sent` |
| generated_at | TIMESTAMPTZ | | |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

**Sample `report_content`:**
```json
{
  "total_score": 85,
  "max_score": 100,
  "per_question_feedback": [
    {
      "question_id": "uuid",
      "question_type": "structured",
      "score": 10,
      "max_score": 10,
      "feedback": "Correct. Polymorphism allows objects of different classes to respond to the same method call."
    },
    {
      "question_id": "uuid",
      "question_type": "structured",
      "score": 0,
      "max_score": 10,
      "feedback": "Incorrect. You selected Compilation (C). The correct answer is Encapsulation (A) — it refers to bundling data with the methods that operate on it."
    },
    {
      "question_id": "uuid",
      "question_type": "non_structured",
      "score": 15,
      "max_score": 20,
      "feedback": "Good identification of two differences between encapsulation and abstraction. However, your example of abstraction was incomplete — you described hiding implementation but did not mention interface exposure."
    }
  ],
  "overall_summary": "You scored 85/100. Strong understanding of core OOP concepts (polymorphism, inheritance). Areas for improvement: encapsulation vs abstraction distinction — review Chapter 3 of the source material for detailed examples."
}
```

---

## 3. Knowledge Service

> Owns the entire **RAG ingestion pipeline** — receives file references from Classification Agent, downloads files from Cloud Object Storage, chunks them, embeds each chunk (via Model Broker), and stores in vector tables. Also stores topic structure.
> Vector tables (Document KB, Policy KB, Enriched KB) are in `vector_schema.md`.
> Knowledge Service does **not** store generated questions, model answers, or evaluation scores — those are all in Assessment Submission Service.

### `topics`

> Stores the topic hierarchy extracted by the Classification Agent (Phase 6). The Q&A Generation Agent reads this to determine *what subtopics to generate questions for* and *how many document chunks to retrieve per subtopic* via the retrieval plan.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| workflow_id | VARCHAR(50) | NOT NULL | Originates from Orchestrator `workflows.id` |
| parent_id | UUID | FK → topics.id, NULL | NULL = main topic, non-NULL = subtopic |
| name | VARCHAR(255) | NOT NULL | e.g. `OOP`, `polymorphism` |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

**Example data:**
```
id: 1, workflow_id: "wf_9f3a21", parent_id: NULL, name: "Object-Oriented Programming"  ← main topic
id: 2, workflow_id: "wf_9f3a21", parent_id: 1,    name: "Encapsulation"                ← subtopic
id: 3, workflow_id: "wf_9f3a21", parent_id: 1,    name: "Polymorphism"                 ← subtopic
id: 4, workflow_id: "wf_9f3a21", parent_id: 1,    name: "Abstraction"                  ← subtopic
```

---

## 4. Orchestrator Agent

> The only stateful agent (Invariant #11). Maintains a LangGraph StateGraph with Redis-backed checkpointing (ADR-29/36). All other agents are stateless.

### `workflows`

> The **source of truth** for `workflow_id`. When the Orchestrator creates a workflow, it generates the `workflow_id` here. All other tables across all services that reference `workflow_id` get their value from this table.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | VARCHAR(50) | **PK** | e.g. `wf_9f3a21` — referenced as `workflow_id` by all other tables across services |
| assessment_id | UUID | NOT NULL | |
| current_phase | VARCHAR(50) | NOT NULL | `material_validation`, `sufficiency_check`, `web_research`, `topic_extraction`, `question_generation`, `quality_validation`, `human_review`, `ready_for_distribution`, `assessment_active`, `evaluation`, `reporting`, `report_review`, `completed`, `terminated` |
| current_agent | VARCHAR(100) | DEFAULT NULL | The agent currently processing this workflow (e.g. `classification-agent`, `qa-generation-agent`, `evaluator-agent`). NULL when no agent is actively processing (e.g. during HITL review, waiting for participants) |
| last_reason_code | VARCHAR(100) | DEFAULT NULL | Updated on every phase transition. The `reason_code` from the latest agent completion event (e.g. `VALIDATION_PASSED`, `INSUFFICIENT_MATERIAL`, `QUALITY_FAILED`, `HARMFUL_CONTENT`). Displayed in assessor dashboard |
| last_reason_message | TEXT | DEFAULT NULL | Updated on every phase transition. Human-readable explanation from the latest event (e.g. "All 3 materials validated successfully", "Weak MCQ distractors detected"). Displayed in assessor dashboard |
| question_set_id | UUID | | Set after question generation approved |
| total_participants | INTEGER | | |
| submissions_completed | INTEGER | NOT NULL, DEFAULT 0 | Count-based — tracks how many participants have submitted. Used to determine when all group members have submitted before triggering group-level evaluation |
| evaluations_completed | INTEGER | NOT NULL, DEFAULT 0 | Count-based completeness check |
| reports_completed | INTEGER | NOT NULL, DEFAULT 0 | Count-based completeness check |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |
| updated_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

> `quality_validation` phase added for Phase 7a (Evaluator feedback loop).

### `workflow_events`

> Records every Pub/Sub event that flows through the Orchestrator — both published and received. Each row captures a single event with the exact payload that was transferred during that publish/subscribe interaction. This provides a durable, append-only audit trail of every state transition in the workflow. See `pubsub.md` for full payload schemas per event type.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| workflow_id | VARCHAR(50) | FK → workflows.id, NOT NULL | |
| event_type | VARCHAR(100) | NOT NULL | Full Pub/Sub topic name in dot-notation. e.g. `assessorflow.workflow.start`, `assessorflow.classification.complete`, `assessorflow.qa-generation.complete`, `assessorflow.quality-validation.complete` |
| source_agent | VARCHAR(100) | NOT NULL | Which agent published this event |
| summary | VARCHAR(500) | NOT NULL | Human-readable description of what happened in this event. Generated by the Orchestrator when recording the event (e.g. "Topic extraction and subtopic identification complete.", "Failed quality validation. Weak MCQ distractors detected."). Displayed in the Agent Trace timeline UI |
| payload | JSONB | | The exact Pub/Sub message payload at the time of publish/subscribe (see `pubsub.md` Section 5 for payload schemas) |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

> Append-only audit log — INSERT only, never UPDATE or DELETE.
> Event naming convention: dot-notation matching Pub/Sub topic names (e.g. `assessorflow.workflow.start`).
> **Written by:** The Orchestrator internally — every time the Orchestrator publishes or receives a Pub/Sub event, it records a row here with a human-readable `summary`. The Orchestrator generates the summary based on the `event_type` and `payload` content (e.g. for `quality-validation.failed`, it includes the rejection reasons from `payload.feedback.issues`). The summary is served via `GET /api/v1/workflows/{id}/events` (4.4) for the Agent Trace timeline UI.

> **Redis cache**: `workflow:{workflow_id}` → hot state for fast reads during active workflows. See `redis_store.md` for full Redis key design.

---

## 5. Email Service

### `email_log`

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| workflow_id | VARCHAR(50) | | Originates from Orchestrator `workflows.id` |
| recipient_email | VARCHAR(255) | NOT NULL | |
| email_type | VARCHAR(50) | NOT NULL | `assessor_review`, `participant_invitation`, `participant_report` |
| subject | VARCHAR(255) | NOT NULL | |
| status | VARCHAR(50) | NOT NULL, DEFAULT 'queued' | `queued`, `sent`, `failed` |
| sent_at | TIMESTAMPTZ | | |
| error_message | TEXT | | If failed |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

> `email_type` values map to the 3 Pub/Sub email flows: assessor-review, assessment-link (participant invitation), participant-report.

---

## 6. Decision Audit Service (L-11)

> Shared infrastructure service. All agents write here via gRPC fire-and-forget. These tables are append-only — INSERT only, never UPDATE or DELETE. (Invariant #5)

### `agent_decision_log`

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| workflow_id | VARCHAR(50) | NOT NULL | Originates from Orchestrator `workflows.id` |
| agent_name | VARCHAR(100) | NOT NULL | e.g. `classification-agent`, `evaluator-agent` |
| decision_type | VARCHAR(100) | NOT NULL | e.g. `material_sufficiency`, `question_quality_validation`, `grading` |
| input_summary | JSONB | | Summarised input to the decision |
| output_summary | JSONB | | Summarised output/result |
| reasoning_steps | JSONB | | Structured reasoning trace (L1/L2/L3 explainability) |
| confidence_score | DECIMAL(3,2) | | 0.00–1.00 |
| prompt_version | VARCHAR(100) | | Format: `{agent}/{template}@v{version}` (ADR-39) |
| model_id | VARCHAR(100) | | Which LLM model was used |
| grounding_sources | JSONB | | Source chunk_ids used for this decision |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

> Dual-writes to SQL (this table, for compliance) + Langfuse (for operational visibility) per ADR-40.

### `token_usage_ledger`

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| workflow_id | VARCHAR(50) | NOT NULL | Originates from Orchestrator `workflows.id` |
| agent_name | VARCHAR(100) | NOT NULL | |
| model_id | VARCHAR(100) | NOT NULL | Which LLM model was invoked |
| prompt_tokens | INTEGER | NOT NULL | |
| completion_tokens | INTEGER | NOT NULL | |
| total_tokens | INTEGER | NOT NULL | |
| estimated_cost_usd | DECIMAL(10,6) | | Estimated cost |
| prompt_version | VARCHAR(100) | | Format: `{agent}/{template}@v{version}` |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

> Supports cost monitoring, Model Broker budget enforcement, and experiment cost tracking via Langfuse.

### `evaluation_audit_log`

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| workflow_id | VARCHAR(50) | NOT NULL | Originates from Orchestrator `workflows.id` |
| evaluation_id | UUID | NOT NULL | References evaluations in Assessment Submission Service |
| participant_id | UUID | NOT NULL | |
| question_id | UUID | NOT NULL | |
| evaluation_phase | VARCHAR(50) | NOT NULL | `quality_validation` (Phase 7a) or `participant_grading` (Phase 10) |
| decision | VARCHAR(50) | NOT NULL | e.g. `pass`, `fail`, `regenerate`, `score_assigned` |
| reasoning | JSONB | | Full reasoning trace |
| grounding_sources | JSONB | | chunk_ids used |
| model_id | VARCHAR(100) | | |
| prompt_version | VARCHAR(100) | | |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

> Separate from `evaluation_details` (which stores results). This table captures the decision process for grading transparency and Responsible AI evidence.

---

## Entity Relationship Summary

```
Identity & Access:
  users 1──∞ sessions

Assessment Submission (central record keeper):
  assessment_configs 1──∞ assessment_participants
  assessment_configs 1──∞ participant_groups
  assessment_configs 1──∞ assessment_materials
  assessment_configs 1──∞ assessment_rubrics
  assessment_configs 1──1 approved_question_sets
  assessment_configs 1──∞ question_sets (drafts, written by Q&A Gen Agent)
  participant_groups 1──∞ participant_group_members
  assessment_participants 1──∞ participant_group_members
  approved_question_sets 1──∞ approved_questions
  question_sets 1──∞ generated_questions (drafts, written by Q&A Gen Agent)
  assessment_configs 1──∞ participant_submissions
  participant_submissions 1──∞ participant_answers
  approved_questions 1──∞ participant_answers
  participant_submissions 1──1 evaluations (written by Evaluator Agent)
  evaluations 1──∞ evaluation_details (written by Evaluator Agent)
  group_evaluations 1──∞ evaluation_details (via group_evaluation_id)
  group_evaluations → participant_groups (group_id)
  group_evaluations → approved_questions (question_id)
  evaluations 1──1 participant_reports (written by Reporting Agent)

Knowledge Service:
  topics (self-referencing: parent_id → topics.id)
  (vector tables: see vector_schema.md)

Orchestrator:
  workflows (PK: id) 1──∞ workflow_events

Decision Audit (L-11):
  agent_decision_log (append-only)
  token_usage_ledger (append-only)
  evaluation_audit_log (append-only)

Email:
  email_log (append-only send records)
```

---

## Cross-Service References

Services reference each other by ID but **do not join across tables owned by other services**.

**Communication rules:**
- **All agents connect to the Orchestrator** — the Orchestrator is the central hub (Invariant #2)
- **No direct agent-to-agent communication** — all inter-agent routing goes through Orchestrator via Pub/Sub
- **Agents read/write data through backend services** (Assessment Submission, Knowledge Service) via **gRPC** (sync)
- **All agents to Decision Audit (L-11)** use **gRPC fire-and-forget** (non-blocking)

| From | To | Via | Notes |
|------|----|-----|-------|
| All agents | Orchestrator Agent | Pub/Sub | Hub-and-spoke — all inter-agent routing |
| Orchestrator | All agents | Pub/Sub | Triggers agent execution per workflow phase |
| Assessment Submission | Identity Service (assessor_id) | gRPC | Auth validation |
| Classification Agent | Knowledge Service (store topics) | gRPC | Phase 6 topic extraction |
| Classification Agent | Assessment Submission (materials, config) | gRPC | Phase 4 sufficiency check |
| Q&A Generation Agent | Knowledge Service (topics, document chunks) | gRPC | Phase 7 similarity search |
| Q&A Generation Agent | Assessment Submission (write drafts, read config) | gRPC | Phase 7 write generated Q&A, read assessment config |
| Evaluator Agent | Assessment Submission (read drafts for validation) | gRPC | Phase 7a quality validation — reads generated Q&A |
| Evaluator Agent | Assessment Submission (read submissions, write scores) | gRPC | Phase 10 participant grading — reads answers, writes evaluations |
| Evaluator Agent | Knowledge Service (source document chunks) | gRPC | Phase 10 grounding for non-structured evaluation |
| Reporting Agent | Assessment Submission (read scores + Q&A, write reports) | gRPC | Phase 11 — reads everything, writes participant_reports |
| Email Service | Orchestrator | Pub/Sub | Receives email trigger events |
| All agents | Decision Audit L-11 (audit writes) | gRPC fire-and-forget | Non-blocking append-only audit |