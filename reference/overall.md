# AssessorFlow — Project Context Document

> **Project:** SWE5008 - Graduate Certificate in Architecting Scalable Systems  
> **Team:** Team 9, MTech SE 33rd Intake, NUS ISS  
> **Last Updated:** 19 March 2026

---

## 1. Overview

AssessorFlow is a modular, multi-agent AI platform that automates the complete knowledge assessment lifecycle — from source material ingestion to the delivery of personalised participant feedback. It assigns routine evaluation tasks to specialised AI agents, reducing operational burden on assessors (educators, corporate trainers) while ensuring rapid and consistent outcomes.

### Problem Statement

An instructor preparing an assessment must manually: read material, design questions, create the assessment interface, distribute it, collect responses, grade answers, and write improvement feedback. This chain is repetitive, time-consuming, and difficult to scale — especially when feedback needs to be personalised at the question level.

### Sample Scenario: English Language Proficiency

An international student preparing for English-medium study in Singapore would like to have better language proficiency before arrival. Yet traditional preparation is inefficient: instructors manually source diverse materials (academic articles, news stories, historical texts, travel narratives, etc.), design targeted grammar and comprehension exercises, grade submissions individually, and write personalised feedback. This workflow is repetitive, time-consuming, and difficult to scale.

AssessorFlow solves this through a material-agnostic design. Instructors upload any source material; the system automatically evaluates it for English language proficiency to parse for grammar patterns, comprehension depth, and expression quality. Instead of manual question design and grading, the platform generates two types of assessments: individual grammar-focused MCQs (verb tenses, articles, prepositions) and group-based comprehension tasks. Instructors have a streamlined UX, only having to choose the source material and select a difficulty level. No need for custom prompts or tweaking. The system then handles question generation, evaluation, and personalised feedback delivery, enabling rapid and scalable English prep for growing international cohorts.

### Target Audience

- **Primary:** Primary school students (the system targets this level, with fixed purpose options provided as dropdowns)
- **Stakeholders:**
  - **Assessors** — educators, trainers, interviewers (reduced workload, consistent output, oversight controls)
  - **Participants** — students, candidates (fast turnaround, targeted feedback)
  - **Org Owners** — businesses, institutions (cost efficiency, scalability, audit trails)
  - **System Admins** — IT/platform operators (security, reliability, deployment simplicity)

---

## 2. Architecture Overview

### Architecture Style

- **Microservice-based** — each agent/service is independently deployable
- **Event-driven** — services communicate asynchronously via Google Pub/Sub
- **Synchronous gRPC** — used for agent-to-service calls where a response is needed before proceeding (e.g., agents calling Knowledge Service, Assessment Submission Service)

### Infrastructure Components

| Component            | Technology / Purpose                                      |
| -------------------- | --------------------------------------------------------- |
| Message Broker       | Google Pub/Sub                                            |
| SQL Database         | Relational DB (per-service ownership)                     |
| Vector Database      | For RAG similarity search (Knowledge Service)             |
| KV Store             | Redis — workflow state hot cache, user context cache      |
| Object Storage       | Cloud object storage for uploaded materials               |
| Container Orchestration | Kubernetes Cluster                                     |
| API Gateway          | Entry point for client requests                           |
| Load Balancer        | Traffic distribution within the cluster                   |
| CDN                  | Optional, for static assets                               |
| WAF                  | Web Application Firewall                                  |
| IGW                  | Internet Gateway (for Web Research Agent external access)  |
| Observability        | Grafana, Prometheus, etc.                                 |
| Frontend             | React JS                                                  |
| ML Model Hosting     | Vertex AI Endpoint (Material Readiness Checker — blur/readability classification) |
| Vision / OCR         | Vertex AI Vision (OCR text extraction, image analysis — used by Validator Agent) |

---

## 3. Services & Ownership

### Service Inventory (12 services + 1 ML endpoint)

| #  | Service                        | Owner       | Purpose                                                                                         |
| -- | ------------------------------ | ----------- | ----------------------------------------------------------------------------------------------- |
| 1  | Identity and Access Service    | Thet Naung Soe | User authentication, user profile, access control, session/token handling                       |
| 2  | Assessment Submission Service  | Thet Naung Soe | Assessment config storage, uploaded resource refs, approved questions, participant answers       |
| 3  | Knowledge Service              | Thet Naung Soe | RAG pipeline — document chunking, vector embedding, similarity search, topic structure storage. Receives pre-extracted text from Validator Agent (no longer downloads files directly) |
| 4  | Classification Agent               | Other          | Material sufficiency check, topic classification, subtopic identification/extraction. Reads chunks already stored by Validator Agent |
| 5  | Orchestrator Agent             | Other          | Central state management, event routing between agents via Pub/Sub                              |
| 6  | Web Research Agent             | Other          | External web search for supplementary material                                                  |
| 7  | Question and Answer Generation Agent | Other | Generates questions + model answers based on subtopics + user config + retrieved knowledge chunks |
| 8  | Evaluator Agent               | Other          | Validates generated questions + model answers (feedback loop); scores participant responses (individual + group) |
| 9  | Reporting Agent                | Other          | Generates per-question feedback + overall summary feedback per participant                      |
| 10 | Email Service                  | Thet Naung Soe | Sends emails (question review link to assessor, final reports to participants)                   |
| 11 | Material Readiness Checker     | Thet Naung Soe | Self-trained model on Vertex AI — validates document/PDF readability. Now used as a **tool** by the Validator Agent (#12) rather than the sole decision-maker |
| 12 | Validator Agent                | Other          | Centralized quality gate for all content entering the system. Wraps MRC (#11) for blur/readability, adds OCR via Vertex AI Vision, and content safety gating (harmful content, PII, copyright). Runs post-Orchestrator in Phase 3 (materials) and Phase 5 (web research). Uses Terminal Signal Contract (`PROCEED`/`TERMINATE`) |

### Thet Naung Soe's Responsibilities

- **Entire frontend** (React JS)
- **Identity and Access Service**
- **Assessment Submission Service**
- **Knowledge Service** (RAG pipeline)
- **Classification Agent**
- **Database schema design** (whole system)
- **API contracts** (interface definitions between all microservices)
- **Message queue schemas** (Google Pub/Sub topic/message definitions)

---

## 4. Communication Patterns

### Asynchronous (Google Pub/Sub)

All inter-agent routing goes through the **Orchestrator Agent**. Agents do not communicate directly with each other. They publish events to Pub/Sub topics, and the Orchestrator subscribes, updates state, and routes to the next agent.

### Synchronous (gRPC)

Used when an agent needs a response before continuing:

- **Validator Agent → Assessment Submission Service** (read material metadata and storage paths in Phase 3 and Phase 5)
- **Validator Agent → Knowledge Service** (pass extracted text for chunking + embedding in Phase 3; pass web research text in Phase 5)
- **Classification Agent → Knowledge Service** (read chunks for sufficiency check + topic extraction; store topic structure)
- **Classification Agent → Assessment Submission Service** (read assessment config — question counts needed for sufficiency check)
- **Web Research Agent → Assessment Submission Service** (register collected text + images as materials in Phase 5)
- **Web Research Agent → Knowledge Service** (read existing document chunks to avoid duplicating already-covered content)
- **Question and Answer Generation Agent → Knowledge Service** (retrieve topics and document chunks via similarity search)
- **Question and Answer Generation Agent → Assessment Submission Service** (write generated Q&A drafts, read config)
- **Evaluator Agent → Assessment Submission Service** (read Q&A drafts for Phase 7a validation; read submissions + write scores for Phase 10)
- **Evaluator Agent → Knowledge Service** (retrieve source document chunks for grounding + search Policy KB for system defaults and assessor rubric)
- **Reporting Agent → Assessment Submission Service** (read scores + Q&A + answers, write reports)
- **Any service → Identity and Access Service** (auth validation)

### State Management

- **Redis (KV Store):** Hot/active workflow state (cache-aside with TTL), user context cache (user_id, workflow_id, role), participant session cache
- **SQL DB (Orchestrator):** Durable workflow records for persistence
- The Orchestrator Agent is **stateful** — it maintains a LangGraph StateGraph with Redis-backed checkpointing (ADR-29/36). All other agents (Classification, Validator, Q&A Generation, Evaluator, Web Research, Reporting) are **stateless** microservices

---

## 5. End-to-End Workflow

### Phase 1: Authentication & Setup

1. **Assessor signs in** → Identity and Access Service handles authentication
2. User context (user_id, workflow_id: null, role) is **cached in Redis** for distributed access — no permissions, access control is role-based only

### Phase 2: Assessment Submission

3. **Assessor submits learning resources + assessment configuration** → Assessment Submission Service
4. Learning materials stored in **cloud object storage** (no validation at upload time — files are stored as-is)
5. **(Optional) Assessor uploads marking rubric** in a **separate upload area** from learning materials. The rubric defines how the assessor wants grading to be performed. Rubric files (PDF/DOCX) are stored in Cloud Object Storage. Rubric processing (chunking, embedding, storing in Policy KB) happens later inside the workflow, after material validation passes.
6. Assessment configuration payload:
   ```json
   {
     "assessment_title": "OOP Mid-Term Quiz",
     "purpose": "topic_revision",
     "duration": "60 minutes",
     "difficulty_level": "easy",
     "participants": [
       "student1@email.com",
       "student2@email.com",
       "student3@email.com",
       "student4@email.com"
     ],
     "question": {
       "structured_question": 6,
       "non_structured_question": 2
     },
     "groups": [
       {
         "group_name": "Group A",
         "members": ["student1@email.com", "student2@email.com"]
       },
       {
         "group_name": "Group B",
         "members": ["student3@email.com", "student4@email.com"]
       }
     ]
   }
   ```
7. **Business Rules:**
   - `structured_question` = MCQ (individual)
   - `non_structured_question` = open-ended (group work)
   - If `non_structured_question > 0`, the assessor **must** define participant groups
   - `purpose` is selected from a fixed dropdown list
8. **Assessor clicks "Start Workflow"** → Assessment Submission Service publishes `assessorflow.workflow.start` event via Pub/Sub
9. Redis user context updated: `workflow_id: null` → `workflow_id: <wf_id>`

### Phase 3: Material Validation (Validator Agent)

10. **Orchestrator** receives `assessorflow.workflow.start` → creates `workflows` record → enters `material_validation` phase
11. **Orchestrator** publishes `assessorflow.validation.trigger` with `assessment_id` → **Validator Agent** (#12)
12. **Validator Agent** calls Assessment Submission Service (`GetMaterials` gRPC) to retrieve material metadata — filters by `readiness_status = NULL` to find unvalidated files only
13. **Validator Agent** downloads each unvalidated file from Cloud Object Storage using `storage_path`, then processes:
    - Calls **Material Readiness Checker** (#11, Vertex AI Endpoint) as a tool — checks blur/readability
    - Calls **Vertex AI Vision** for OCR — extracts text content from PDFs/documents
    - Performs **content safety check** (LLM reasoning) on the extracted text — detects harmful content, PII, copyright violations
    - Returns a **Terminal Signal** per file: `PROCEED` or `TERMINATE` with `reason_code` and `message`
14. **If `PROCEED`** → Validator Agent passes extracted text to **Knowledge Service** via `ProcessMaterial` (gRPC) → Knowledge Service chunks, embeds, stores in `document_chunks`. Validator Agent updates `assessment_materials.readiness_status` → `PROCEED`
15. **If `TERMINATE`** (any file) → workflow is **terminated** by the system. `assessment_configs.status` → `terminated`, `workflows.current_phase` → `terminated`. `assessment_materials.readiness_status` → `TERMINATE` with `validation_reason_code` and `validation_message`. The assessor must create a new assessment.
16. **If all files `PROCEED`** → Validator Agent publishes `assessorflow.validation.complete` → Orchestrator proceeds to Phase 4
17. **(Optional) Rubric processing:** If the assessor uploaded marking rubrics, the Validator Agent also validates and extracts text from rubrics, then passes to Knowledge Service via `ProcessMaterial` with `source = 'rubric'` → stored in `policy_chunks` (Policy KB)

### Phase 4: Material Sufficiency Check (Classification Agent)

18. **Orchestrator** publishes `assessorflow.classification.trigger` (action: `sufficiency_check`) to **Classification Agent**
19. Classification Agent:
    - Retrieves user context (user_id, workflow_id) from Redis
    - Retrieves assessment config from Assessment Submission Service (gRPC)
    - Reads chunks from **Knowledge Service** via `GetChunksByWorkflow` (gRPC) — chunks are already stored by Validator Agent in Phase 3. Uses the chunked content to **check if submitted material is sufficient** for downstream topic extraction and question generation
20. **If material is NOT sufficient** → Classification Agent publishes `assessorflow.classification.insufficient` — two options presented to assessor:
    - **Option A — Resubmit:** User resubmits materials → workflow restarts from the beginning
    - **Option B — Web search support:** User agrees to system search → Orchestrator publishes `assessorflow.web-research.trigger`

### Phase 5: Web Research (if triggered)

21. Assessor chooses web search support → the system prompts the assessor to **enter a topic** for the web search (e.g. "Object-Oriented Programming"). This topic is provided by the assessor via the frontend
22. Orchestrator publishes `assessorflow.web-research.trigger` (with assessor-provided topic) → **Web Research Agent**
23. Web Research Agent:
    - Reads existing document chunks from Knowledge Service to understand what content is already covered
    - Performs web search based on the **assessor-provided topic**
    - Avoids reusing info already covered by existing chunks
    - Accepts **text and image results** — collects text content and downloads images
24. Web Research Agent registers collected content in **Assessment Submission Service** via `UploadWebResearchMaterials` (gRPC 2.2.6) — text saved as `.md` files, images saved as-is. All files stored in Cloud Object Storage. Rows created in `assessment_materials` with `source = 'web_research'`
25. Publishes `assessorflow.web-research.complete` → Orchestrator receives
26. **Orchestrator triggers Validator Agent** via `assessorflow.validation.trigger` (validation_type: `material_validation`) with `assessment_id` — same validation path as Phase 3
27. Validator Agent calls `GetMaterials` → gets all materials including the new web research ones (filtered by `readiness_status = null`)
28. Validator Agent validates web research materials: blur detection (MRC) + OCR (Vertex AI Vision) + content safety check. Returns Terminal Signal per file
29. **If `PROCEED`** → Validator Agent passes extracted text to Knowledge Service → stored in `enriched_chunks`. Publishes `assessorflow.validation.complete`
30. **If `TERMINATE`** → web research content rejected. Orchestrator routes back to Classification Agent with original materials only
31. Orchestrator routes to Classification Agent via `assessorflow.classification.trigger` (action: `topic_extraction`)
32. Classification Agent now has sufficient material (original + enriched if validated) → proceeds

### Phase 6: Topic Extraction (Classification Agent)

33. Classification Agent reads chunks from **Knowledge Service** via `GetChunksByWorkflow` (gRPC) and analyzes them to identify **main topic** and **subtopics**
    - Example: OOP material → class, object, attributes, methods, polymorphism, interface, abstraction, encapsulation
    - Example: World History → Ancient Civilizations, Industrial Revolution, etc.
34. **Synchronously** sends extracted topic structure to **Knowledge Service** for storage (gRPC)
35. Knowledge Service stores main topic + subtopics, returns success
36. Classification Agent publishes `assessorflow.classification.complete` to Orchestrator

### Phase 7: Question and Answer Generation (Question and Answer Generation Agent)

37. Orchestrator publishes `assessorflow.qa-generation.trigger` → **Question and Answer Generation Agent**
38. Question and Answer Agent retrieves:
    - User context from Redis
    - Classified topics (main topic + subtopics) from **Knowledge Service** (provided by Classification Agent)
    - Assessment config from **Assessment Submission Service**
39. **Subtopic selection + retrieval planning** — the Q&A Generation Agent does NOT generate its own queries. It receives the full list of subtopics from Knowledge Service (e.g. 20 subtopics extracted by Classification Agent), then **selects a subset** based on the assessment config (question count, difficulty level, purpose). The selected subtopic names are used directly as similarity search queries:
    ```json
    {
      "workflow_id": "wf_9f3a21",
      "queries": [
        { "query_text": "Ancient Civilizations", "top_k": 3 },
        { "query_text": "Industrial Revolution", "top_k": 1 }
      ]
    }
    ```
    - The Q&A Gen Agent selects which subtopics to use and how many chunks to retrieve per subtopic (`top_k`) based on purpose, question count, and difficulty level
    - Example: 20 subtopics available, config asks for 8 questions → agent selects 4 subtopics, allocates questions across them
40. **Synchronously** calls Knowledge Service with the retrieval plan (gRPC)
41. Knowledge Service performs **pure semantic similarity search** — embeds the subtopic name (query text) and compares against stored chunk embeddings using cosine similarity. Returns top-k most relevant chunks per query:
    ```json
    {
      "results": [
        {
          "chunk_id": "chunk_101",
          "query_text": "Ancient Civilizations",
          "content": "Ancient civilizations such as Mesopotamia, Egypt...",
          "score": 0.92,
          "metadata": { "source": "world_history.pdf", "page": 2 }
        },
        {
          "chunk_id": "chunk_205",
          "query_text": "Industrial Revolution",
          "content": "The Industrial Revolution began in Britain...",
          "score": 0.88,
          "metadata": { "source": "world_history.pdf", "page": 5 }
        }
      ]
    }
    ```
42. Generates **questions + model answers** based on config + retrieved chunks
43. For MCQ questions, also generates **per-option explanations** (ADR-44) — why the correct answer is right and why each distractor is wrong. Stored in `generated_questions.metadata.option_explanations`
44. Stores questions + model answers + option explanations in **Assessment Submission Service** (`question_sets` + `generated_questions` tables, linked to source chunk_ids for traceability)
45. Publishes `assessorflow.qa-generation.complete` to Orchestrator

### Phase 7a: Question Quality Feedback Loop (Evaluator Agent)

46. Orchestrator receives `assessorflow.qa-generation.complete` → triggers **Evaluator Agent** for quality validation (via Pub/Sub)
47. Evaluator Agent retrieves the generated questions + model answers from **Assessment Submission Service** (`generated_questions` table)
48. Evaluator Agent validates:
    - Are the questions grounded in the source material?
    - Are the model answers accurate and complete?
    - Do questions match the requested difficulty level and type?
    - For MCQ: are the **per-option explanations** accurate and consistent with the correct answer?
49. **If NOT satisfied** → Evaluator Agent publishes `assessorflow.quality-validation.failed` → Orchestrator re-triggers Q&A Generation Agent → regeneration cycle (loop back to step 42)
50. **If satisfied** → Evaluator Agent publishes `assessorflow.quality-validation.complete` to Orchestrator
51. Orchestrator updates workflow state: `question_set_id = <value>`

### Phase 8: Human-in-the-Loop Review

52. **Orchestrator** publishes `assessorflow.email.request.assessor-review` event → **Email Service** sends the assessor an email with a link to review the generated question set
53. Assessor accesses the system → reviews questions + reviews MCQ option explanations → **removes questions they don't like**
54. Assessor **approves** the final question set
55. Assessment Submission Service copies approved questions from `generated_questions` to `approved_questions` (both tables are in the same service — including `option_explanations` in metadata), marks removed questions as `was_approved = false`, and publishes `assessorflow.human-review.approved` event to Orchestrator
56. Orchestrator transitions workflow to `ready_for_distribution` phase — assessment is approved and ready for participant invitations

**Data ownership:**
- **Assessment Submission Service** — stores everything: `generated_questions` (drafts + all iterations for audit trail) AND `approved_questions` (final approved set). Single source of truth for the entire question lifecycle.
- **Knowledge Service** — stores topics and document chunks only (not questions or answers)
- **Runtime agents do not own database tables** — Q&A Generation Agent and Evaluator Agent read from and write to Assessment Submission Service via gRPC

### Phase 9: Participant Assessment

57. **Assessor selects participants** to invite from the distribution queue screen → clicks "Send Invitations" → `POST /api/v1/assessments/{id}/invite` with `participant_ids[]`
58. Assessment Submission Service publishes `assessorflow.email.request.assessment-link` per selected participant → **Email Service** sends invitation emails → `assessment_configs.status` → `assessment_active`, `workflows.current_phase` → `assessment_active`
59. Participant clicks the assessment link → Assessment Submission Service validates the link:
    - Verifies the `participant_id` + `assessment_id` combination exists in `assessment_participants`
    - Checks `invitation_status` is valid
    - Checks the assessment deadline has not passed
    - Creates a `participant:{email}` session in Redis (see `redis_store.md` Section 2) with TTL = `duration_minutes + buffer`
    - Creates a `participant_submissions` record with `status = 'in_progress'` and `started_at = now()`
    - Updates `assessment_participants.invitation_status` → `accepted`
60. Participant takes the assessment — the system enforces time limitations via the Redis session TTL
61. Each participant's answers are stored in **Assessment Submission Service** (`participant_answers` table)
62. For each participant who completes → Assessment Submission Service updates `participant_submissions.status` → `submitted`, sets `submitted_at`, and publishes `assessorflow.participant.submission-completed`

**Note:** Participants are **not** registered users. They do not have accounts in the `users` table. Access is controlled through:
- **Unique assessment link** — contains encoded `assessment_id` + `participant_id`, tied to the email in `assessment_participants`
- **Redis session** (`participant:{email}`) — tracks active assessment-taking with a TTL matching the assessment duration. Auto-expires after time limit, preventing late submissions
- **Trackability** — every participant action is tied to `assessment_participants.id` → their submission, answers, evaluation, and report all chain back to this ID
- **Secure exam environment** is a future concern — current scope focuses on link-based access with time enforcement

### Phase 10: Participant Evaluation (Evaluator Agent)

63. Orchestrator receives `assessorflow.participant.submission-completed` → publishes `assessorflow.evaluation.trigger` to **Evaluator Agent**
64. Evaluator Agent retrieves from **Assessment Submission Service**:
    - Participant submission (answers)
    - Approved questions + model answers
    - Source chunk_ids from question metadata
65. Evaluator Agent retrieves from **Knowledge Service**:
    - Source document chunks (via source_chunk_ids) — the same chunks used during question generation, ensuring evaluation is grounded against the exact same source material
    - **Policy chunks** (via similarity search on Policy KB) — both system default grading guidance AND the assessor's uploaded marking rubric (if provided in Phase 2). The rubric provides specific grading criteria the assessor wants followed
66. Evaluator Agent grades using: model answers + source document chunks + rubric policies
67. Evaluator Agent **records rubric-grounded reasoning** in `evaluation_audit_log` (via Decision Audit L-11) — captures which rubric sections were used for each grading decision. This provides **explainability** — the frontend can show exactly how the agent applied the rubric to arrive at each score
68. Evaluator Agent **writes scores back to Assessment Submission Service** (`evaluations`, `evaluation_details`, `group_evaluations` tables)

**Key distinction — structured vs non-structured evaluation:**
- **Structured questions (MCQ)** are **deterministic** — the correct answer was already generated by the Question and Answer Generation Agent and validated by the Evaluator Agent during the feedback loop (Phase 7a). At marking time, no LLM is needed; the system simply compares the participant's selected answer against the pre-validated correct answer.
- **Non-structured questions (open-ended)** require **LLM-based evaluation** — the Evaluator Agent uses the model answer + source document chunks + **assessor rubric** as grounding to assess the quality of the participant's free-text response. The rubric reduces AI hallucination by anchoring grading decisions in the assessor's own criteria.

**Decision branch — Does the assessment include non-structured questions?**

**Path A — No (structured only):**
69. Evaluates all submitted answers for the participant using **deterministic matching** against pre-validated correct answers (no LLM required)
70. Writes scores to Assessment Submission Service → publishes `assessorflow.evaluation.complete` to Orchestrator

**Path B — Yes (mixed structured + non-structured):**
69. Two parallel evaluation tracks:
    - **Structured answers** → **deterministic matching** per participant individually (no LLM)
    - **Non-structured answers** → **LLM-based evaluation** at **group level** using model answers + source chunks as grounding (waits for all group members to submit first)
70. **Aggregator** consolidates non-structured evaluation results → assigns the **same group mark to all members**
71. Writes all scores to Assessment Submission Service → publishes `assessorflow.evaluation.complete` to Orchestrator

72. Orchestrator updates evaluation progress
73. **Completeness check:** Verifies that the number of evaluated question sets matches the number of participants before proceeding

### Phase 11: Reporting (Reporting Agent)

74. Orchestrator confirms all evaluations complete → publishes `assessorflow.reporting.trigger` to **Reporting Agent**
75. Reporting Agent reads from **Assessment Submission Service** (single source for everything):
    - Questions presented (from `approved_questions`)
    - Participant's submitted answers (from `participant_answers`)
    - Scores awarded by Evaluator Agent (from `evaluations` + `evaluation_details`)
76. Reporting Agent generates **two levels of feedback**:
    - **Per-question feedback** — for MCQ: serves pre-generated `option_explanations` (ADR-44) directly, no LLM call. For non-structured: LLM-generated feedback referencing model answer and source material
    - **Overall summary feedback** — aggregates performance across all questions, highlights strengths, identifies areas for improvement, and provides personalised study recommendations
77. The combined per-question feedback + overall summary constitutes the **participant summary report**
78. Reporting Agent **writes the report back to Assessment Submission Service** (`participant_reports` table)
79. Reporting is executed **per participant**
80. System enforces a **maximum number of concurrent reporting instances** to control infrastructure cost
81. Each report completion → publishes `assessorflow.reporting.complete` to Orchestrator
82. Orchestrator updates reporting progress
83. **Completeness check:** Verifies that the number of completed reports matches the number of participants

### Phase 12: Report Review & Distribution

84. When completed reports = number of participants → Orchestrator transitions to `report_review` phase
85. **Assessor reviews reports** — accesses the detailed report view via `GET /api/v1/assessments/{id}/reports`, which shows per-question evaluation detail: participant answer vs model answer, scores, AI reasoning, rubric match, grounding citations, and MCQ option explanations
86. **Assessor distributes reports** — clicks "Send Reports" via `POST /api/v1/assessments/{id}/reports/distribute`
87. Assessment Submission Service publishes `assessorflow.email.request.participant-report` (one per participant) → **Email Service** sends report/feedback to each participant
88. Orchestrator publishes `assessorflow.workflow.complete` → workflow marked as **completed**
89. **End of workflow**

---

## 6. Key Design Patterns

### Orchestrator as Central Router
- All inter-agent communication goes through the Orchestrator via Pub/Sub
- Agents never talk directly to each other
- Orchestrator subscribes to topics and publishes to topics
- Maintains workflow state in Redis (hot) + DB (durable)

### Completeness Checks
- Orchestrator performs count-based completeness checks at every stage transition:
  - All evaluations complete? → proceed to reporting
  - All reports complete? → proceed to workflow completion

### Synchronous for Data Dependencies
- When an agent needs data before continuing, it uses synchronous gRPC calls (e.g., to Knowledge Service, Assessment Submission Service)
- When an agent just needs to signal "I'm done," it uses async Pub/Sub

### Data Ownership
- All services share a single PostgreSQL database — logical ownership per service (each service owns its tables, no cross-service table access)
- Assessment Submission Service is the central record keeper — owns config, materials, generated questions (drafts + approved), submissions, evaluation scores, and reports
- Runtime agents (Classification, Q&A Generation, Evaluation, Web Research, Reporting) do not own database tables — they read/write through backend services via gRPC

### Human-in-the-Loop
- Single human intervention point: assessor reviews and approves generated questions
- All other agent-to-agent transitions are fully automated

### Email Service Touchpoints
1. Sends assessor a link to review generated questions (after question generation — Phase 8)
2. Sends participants their assessment link (after assessor approves questions — Phase 9)
3. Sends participants their reports (after assessor reviews and distributes reports — Phase 12)

---

## 7. Data Model Concepts (High-Level)

### Assessment Submission Service (central record keeper)
- Assessment config (purpose, duration, difficulty, question counts)
- Participant list + group definitions
- Resource references (links to object storage — learning materials + marking rubrics in separate upload areas)
- Draft questions + model answers (`question_sets` + `generated_questions` — written by Q&A Generation Agent during Phase 7. If the Evaluator Agent rejects during Phase 7a, `question_sets.iteration_count` increments and new `generated_questions` rows are created with the next `iteration` number. Previous iterations are retained for audit trail. Once validated and assessor-approved, kept questions are copied to `approved_questions`)
- Approved question set (final questions kept by assessor)
- Participant submissions + answers
- Evaluation scores (written by Evaluator Agent — per-participant and per-question breakdowns, group evaluations)
- Participant reports (written by Reporting Agent — per-question feedback + overall summary)

### Knowledge Service
- Topic structure (main topic + subtopics per workflow)
- Document chunks (vector embeddings for similarity search)
- Policy chunks (vector embeddings for assessment rules/rubrics — system-wide defaults managed by Admin via `POST /api/v1/admin/policies`, plus per-assessment assessor rubrics uploaded via `POST /api/v1/assessments/{id}/rubrics`)
- Enriched chunks (vector embeddings for web research results)
- Does **not** store questions, answers, scores, or reports — those are all in Assessment Submission Service

### Orchestrator Agent (only stateful agent)
- Workflow state (Redis hot cache + PostgreSQL durable storage + LangGraph checkpoint)
- Tracks: workflow_id, current phase, question_set_id, evaluation progress, reporting progress

### Identity and Access Service
- User profiles, credentials
- Roles (assessor, admin) — role-based access control
- Session/token data (refresh tokens in DB, access tokens stateless)
- Cached in Redis for distributed access

### Runtime Agents (no database ownership)
- Classification Agent, Validator Agent, Q&A Generation Agent, Evaluator Agent, Web Research Agent, Reporting Agent
- Pure compute — read from and write to backend services (Assessment Submission, Knowledge Service) via gRPC
- Audit data written to Decision Audit Service (L-11) via gRPC fire-and-forget

---

## 8. Responsible AI & Security

- All AI responses rooted in traceable source documents
- Human review of AI-generated content (question approval)
- OWASP LLM Top 10 (2025) and OWASP Agentic Security Initiative (ASI) mapped
- Prompt guardrails
- MLSecOps/LLMSecOps pipeline with quality gates
- Versioned embeddings
- Pipeline monitoring and drift detection with CI/CD

---

## 9. Assumptions & Constraints

- The system uses simulated assessment data and is not intended for production examination use
- The project focuses on demonstrating AI engineering practices; domain richness is intentionally simplified
- The project is greenfield but follows established architectural patterns
- Participants are not registered users — they receive invitations; secure exam environment is a future concern
- Material uploads are limited to PDF and DOCX files only