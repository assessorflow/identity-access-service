# AssessorFlow — API Contract

> Language-agnostic API contracts for all services.
> Derived from `overall.md` (workflow), `schema.md` (data), `pubsub.md` (events), `redis_store.md` (cache).
> All request/response bodies are JSON. UUIDs are string-formatted.
> **External APIs** = Frontend → API Gateway → Service (REST over HTTPS).
> **Internal APIs** = Agent → Service (gRPC, documented here as language-agnostic contracts).
> Auth: External APIs use JWT bearer tokens. Internal APIs use mTLS (K8s Workload Identity).

---

## API Best Practices

### Versioning

All external REST endpoints use **URL path versioning**: `/api/v1/...`

- **Current version:** `v1`
- **Breaking changes** (removing fields, changing types, removing endpoints) require a **major version bump** (`v2`)
- **Non-breaking changes** (adding optional fields, adding new endpoints) are added to the current version
- When `v2` is introduced, `v1` remains available for a deprecation period with a `Sunset` header

### Pagination

List endpoints use **cursor-based or offset pagination**:

```json
{
  "items": [...],
  "total": 42,
  "page": 1,
  "page_size": 10
}
```

Query parameters: `?page=1&page_size=10`

### Idempotency

Write operations (POST/PUT) should include an `Idempotency-Key` header for safe retries:

```
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
```

The server returns the same response for duplicate requests with the same key within a retention window.

### Rate Limiting

All external endpoints enforce rate limits. Responses include:

```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 95
X-RateLimit-Reset: 1679616000
```

When exceeded, the server returns `429 Too Many Requests`.

### Content Type

- Request: `Content-Type: application/json` (except file uploads which use `multipart/form-data`)
- Response: `Content-Type: application/json`

### Timestamps

All timestamps use **ISO 8601 format in UTC**: `2026-03-21T10:30:00Z`

---

## 1. Identity and Access Service

> Handles user authentication, session management, and profile retrieval. Only assessors and admins are registered users — participants are not.

### 1.1 Login

**Type:** External
**Phase:** 1
**Caller:** Frontend

```
POST /api/v1/auth/login
```

**Request:**
```json
{
  "email": "assessor@email.com",
  "password": "********"
}
```

**Response (200):**
```json
{
  "access_token": "eyJhbG...",
  "refresh_token": "dGhpc0lz...",
  "expires_in": 3600,
  "user": {
    "id": "uuid",
    "email": "assessor@email.com",
    "full_name": "Thet Naung Soe",
    "role": "assessor"
  }
}
```

**Side effect:** Creates `user:{user_id}` entry in Redis with `{ user_id, workflow_id: "", role }`.

**Errors:**
| Status | Reason |
|--------|--------|
| 401 | Invalid credentials |
| 403 | Account disabled (`is_active = false`) |

---

### 1.2 Refresh Token

**Type:** External
**Caller:** Frontend

```
POST /api/v1/auth/refresh
```

**Request:**
```json
{
  "refresh_token": "dGhpc0lz..."
}
```

**Response (200):**
```json
{
  "access_token": "eyJhbG...",
  "expires_in": 3600
}
```

**Errors:**
| Status | Reason |
|--------|--------|
| 401 | Refresh token expired or invalid |

---

### 1.3 Logout

**Type:** External
**Caller:** Frontend

```
POST /api/v1/auth/logout
```

**Headers:** `Authorization: Bearer {access_token}`

**Response (204):** No content.

**Side effect:** Deletes `sessions` row. Evicts `user:{user_id}` from Redis.

---

### 1.4 Get Current User

**Type:** External
**Caller:** Frontend

```
GET /api/v1/users/me
```

**Headers:** `Authorization: Bearer {access_token}`

**Response (200):**
```json
{
  "id": "uuid",
  "email": "assessor@email.com",
  "full_name": "Thet Naung Soe",
  "role": "assessor",
  "is_active": true,
  "created_at": "2026-03-21T10:00:00Z"
}
```

---

### 1.5 Validate Token (Internal)

**Type:** Internal (gRPC)
**Caller:** Any service needing auth validation

```
ValidateToken(token) → UserContext
```

**Request:**
```json
{
  "access_token": "eyJhbG..."
}
```

**Response:**
```json
{
  "valid": true,
  "user_id": "uuid",
  "role": "assessor"
}
```

---

## 2. Assessment Submission Service

> The central record keeper. Owns assessment config, materials, generated questions (drafts + approved), participant data, submissions, evaluation scores, and reports. All agents read from and write to this service.

> **Frontend "Create New Assessment" flow — single page, single button:**
>
> The assessor fills out the entire form (config, uploads files, adds participants/groups) on one page. When they click **"Start Assessment Workflow"**, the frontend chains these API calls sequentially behind the scenes:
>
> ```
> 1. POST /api/v1/assessments                              [2.1.1 — create config, get assessment_id]
> 2. POST /api/v1/assessments/{id}/materials    (per file)  [2.2.1 — upload each learning resource]
> 3. POST /api/v1/assessments/{id}/rubrics      (per file)  [2.2b.1 — upload each rubric, optional]
> 4. POST /api/v1/assessments/{id}/start                    [2.2.5 — start workflow, publish workflow.start]
> ```
>
> The user sees a single loading state. If any step fails, the frontend shows an error and stops. The APIs are separate for modularity but are always called as a single sequence from the UI.

### 2.1 Assessment Config

#### 2.1.1 Create Assessment

**Type:** External
**Phase:** 2
**Caller:** Frontend (Assessor)

```
POST /api/v1/assessments
```

**Headers:** `Authorization: Bearer {access_token}`

**Request:**
```json
{
  "assessment_title": "OOP Mid-Term Quiz",
  "purpose": "topic_revision",
  "duration_minutes": 60,
  "difficulty_level": "easy",
  "deadline": "2026-03-25T23:59:00Z",
  "structured_question_count": 6,
  "non_structured_question_count": 2,
  "participants": [
    "student1@email.com",
    "student2@email.com",
    "student3@email.com",
    "student4@email.com"
  ],
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

**Validation rules:**
- `purpose` must be from the fixed dropdown list
- `difficulty_level` must be `easy`, `medium`, or `hard`
- If `non_structured_question_count > 0`, `groups` is required and every participant must belong to exactly one group
- All participant emails must be unique within the assessment

**Response (201):**
```json
{
  "id": "uuid",
  "workflow_id": null,
  "status": "draft",
  "purpose": "topic_revision",
  "duration_minutes": 60,
  "difficulty_level": "easy",
  "structured_question_count": 6,
  "non_structured_question_count": 2,
  "participant_count": 4,
  "created_at": "2026-03-21T10:00:00Z"
}
```

**Note:** `workflow_id` is `null` at creation. It is set when the Orchestrator creates the workflow after the assessor clicks "Start Workflow" (2.2.5) and the Orchestrator receives the `assessorflow.workflow.start` event.

**Errors:**
| Status | Reason |
|--------|--------|
| 400 | Validation failed (missing groups, invalid purpose, etc.) |
| 401 | Unauthorized |

---

#### 2.1.2 Get Assessment (External — REST)

**Type:** External (REST)
**Caller:** Frontend → **Assessment Submission Service**
**Phase:** All phases (assessor views assessment details)

```
GET /api/v1/assessments/{assessment_id}
```

**Headers:** `Authorization: Bearer {access_token}`

**Response (200):**
```json
{
  "id": "uuid",
  "assessment_title": "Module 5 Topic Revision",
  "workflow_id": "wf_9f3a21",
  "assessor_id": "uuid",
  "purpose": "topic_revision",
  "duration_minutes": 60,
  "difficulty_level": "easy",
  "structured_question_count": 6,
  "non_structured_question_count": 2,
  "status": "processing",
  "participants": [
    {
      "id": "uuid",
      "email": "student1@email.com",
      "invitation_status": "pending",
      "group": {
        "id": "uuid",
        "group_name": "Group A"
      }
    }
  ],
  "created_at": "2026-03-21T10:00:00Z",
  "updated_at": "2026-03-21T10:30:00Z"
}
```

---

#### 2.1.2b Get Assessment Config (Internal — gRPC)

**Type:** Internal (gRPC)
**Caller:** Classification Agent → **Assessment Submission Service** (Phase 4), Q&A Generation Agent → **Assessment Submission Service** (Phase 7), Evaluator Agent → **Assessment Submission Service** (Phase 10)
**Purpose:** Agents retrieve assessment config (purpose, difficulty, question counts, participant groups) needed for their processing. Same data as 2.1.2 but served via gRPC.

```
GetAssessmentConfig(assessment_id) → AssessmentConfig
```

**Request:**
```json
{
  "assessment_id": "uuid"
}
```

**Response:**
```json
{
  "id": "uuid",
  "assessment_title": "Module 5 Topic Revision",
  "workflow_id": "wf_9f3a21",
  "purpose": "topic_revision",
  "duration_minutes": 60,
  "difficulty_level": "easy",
  "structured_question_count": 6,
  "non_structured_question_count": 2,
  "status": "processing",
  "participant_count": 4,
  "groups": [
    {
      "id": "uuid",
      "group_name": "Group A",
      "member_ids": ["uuid-1", "uuid-2"]
    }
  ]
}
```

---

#### 2.1.3 List Assessments (Assessor)

**Type:** External
**Caller:** Frontend (Assessor dashboard)

```
GET /api/v1/assessments?status={status}&page={page}&page_size={page_size}
```

**Response (200):**
```json
{
  "items": [
    {
      "id": "uuid",
      "assessment_title": "Module 5 Topic Revision",
      "workflow_id": "wf_9f3a21",
      "purpose": "topic_revision",
      "difficulty_level": "easy",
      "status": "active",
      "participant_count": 4,
      "created_at": "2026-03-21T10:00:00Z"
    }
  ],
  "total": 12,
  "page": 1,
  "page_size": 10
}
```

---

### 2.2 Materials

#### 2.2.1 Upload Material

**Type:** External
**Phase:** 2
**Caller:** Frontend (Assessor)

The assessor uploads learning materials that will be used for topic extraction and question generation. Files go **through Assessment Submission Service** (not directly to Cloud Storage) — the service handles storage, creates an `assessment_materials` record, and automatically triggers validation (2.2.3) behind the scenes. The assessor can upload multiple files one at a time. Each upload returns immediately while validation runs asynchronously.

```
POST /api/v1/assessments/{assessment_id}/materials
Content-Type: multipart/form-data
```

**Form fields:**
- `file` — the uploaded file (PDF or DOCX)

**Supported file types:**
| Type | Extension | Validation |
|------|-----------|------------|
| PDF | `.pdf` | Material Readiness Checker (Vertex AI) — checks readability |
| DOCX | `.docx` | Material Readiness Checker (Vertex AI) — checks readability |

**Response (201):**
```json
{
  "id": "uuid",
  "file_name": "world_history.pdf",
  "file_type": "pdf",
  "storage_path": "gs://bucket/assessments/uuid/world_history.pdf",
  "readiness_status": null,
  "source": "upload",
  "created_at": "2026-03-21T10:05:00Z"
}
```

| Field | Type | Notes |
|-------|------|-------|
| id | string (UUID) | `assessment_materials.id` — used to reference this file throughout the workflow |
| file_name | string | Original filename as uploaded by the assessor |
| file_type | string | Detected from file extension: `pdf`, `docx` |
| storage_path | string | Cloud Object Storage path where the file is stored |
| readiness_status | string or null | `null` immediately after upload (not yet validated). Updated to `PROCEED` or `TERMINATE` by Validator Agent during Phase 3/5 |
| source | string | `upload` for assessor-uploaded files. `web_research` for Web Research Agent uploads (2.2.6) |

**Side effects:**
1. File is uploaded to Cloud Object Storage through the service
2. `assessment_materials` row created with `readiness_status = null`

**Note:** Material validation (MRC) does NOT happen at upload time. Files are stored as-is. Validation happens inside the workflow after the assessor clicks "Start Workflow" (2.2.5).

**Errors:**
| Status | Reason |
|--------|--------|
| 400 | Unsupported file type |
| 404 | Assessment not found |
| 413 | File too large |

---

#### 2.2.2 List Materials (External — REST)

**Type:** External (REST)
**Caller:** Frontend → **Assessment Submission Service**
**Phase:** 2, 3 (assessor views uploaded materials and their validation status)

```
GET /api/v1/assessments/{assessment_id}/materials
```

**Headers:** `Authorization: Bearer {access_token}`

**Response (200):**
```json
{
  "items": [
    {
      "id": "uuid",
      "file_name": "world_history.pdf",
      "file_type": "pdf",
      "storage_path": "gs://bucket/assessments/uuid/world_history.pdf",
      "readiness_status": "PROCEED",
      "source": "upload",
      "created_at": "2026-03-21T10:05:00Z"
    },
    {
      "id": "uuid",
      "file_name": "web_research.md",
      "file_type": "md",
      "storage_path": "gs://bucket/assessments/uuid/web_research.md",
      "readiness_status": "PROCEED",
      "source": "web_research",
      "created_at": "2026-03-21T11:00:00Z"
    }
  ]
}
```

---

#### 2.2.2b Get Materials (Internal — gRPC)

**Type:** Internal (gRPC)
**Caller:** Validator Agent (Phase 3/5), Classification Agent (Phase 4/6) → **Assessment Submission Service**
**Purpose:** Retrieves material metadata and storage paths. The Validator Agent calls this to find unvalidated files (`readiness_status = NULL`) for validation. The Classification Agent calls this to reference material metadata.

```
GetMaterials(assessment_id, readiness_status?) → Materials[]
```

**Request:**
```json
{
  "assessment_id": "uuid",
  "readiness_status": null
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` |
| readiness_status | string or null (optional) | Filter: `null` (unvalidated only), `PROCEED` (validated only), `TERMINATE` (rejected only). If omitted, returns all materials |

**Response:**
```json
{
  "materials": [
    {
      "id": "uuid",
      "file_name": "world_history.pdf",
      "file_type": "pdf",
      "storage_path": "gs://bucket/assessments/uuid/world_history.pdf",
      "source": "upload",
      "readiness_status": null,
      "source_url": null
    }
  ]
}
```

---

#### 2.2.3 Material Validation (Inside Workflow — Phase 3, Validator Agent)

**This is NOT a callable endpoint from the frontend.** Material validation is triggered by the Orchestrator as the first step inside the workflow (Phase 3), after the assessor clicks "Start Workflow" (2.2.5). The Orchestrator publishes `assessorflow.validation.trigger` (pubsub.md Topic #2) to the **Validator Agent** (#12).

**How it works:**
1. Assessor clicks "Start Workflow" (2.2.5) → `assessorflow.workflow.start` published → Orchestrator creates workflow → enters `material_validation` phase
2. Orchestrator publishes `assessorflow.validation.trigger` with `assessment_id` → **Validator Agent**
3. Validator Agent calls Assessment Submission Service (`GetMaterials` gRPC 2.2.2b) to retrieve material metadata — filters by `readiness_status = NULL` to find unvalidated files only
4. Validator Agent downloads each unvalidated file from Cloud Object Storage using `storage_path`, then processes:
   - Calls **Material Readiness Checker** (#11, Vertex AI Endpoint) as a tool — checks blur/readability
   - Calls **Vertex AI Vision** for OCR — extracts text content from PDFs/documents
   - Performs **content safety check** (LLM reasoning) on extracted text — detects harmful content, PII, copyright violations
5. Validator Agent returns a **Terminal Signal** per file: `PROCEED` or `TERMINATE` with `reason_code` and `message`
6. **If `PROCEED`** → Validator Agent passes extracted text to Knowledge Service via `ProcessMaterial` (3.2.1) → Knowledge Service chunks, embeds, stores in `document_chunks`. Updates `assessment_materials.readiness_status` → `PROCEED`
7. **If `TERMINATE`** (any file) → workflow is **terminated** by the system. `assessment_configs.status` → `terminated`, `workflows.current_phase` → `terminated`. `assessment_materials.readiness_status` → `TERMINATE` with `validation_reason_code` and `validation_message`. The assessor must create a new assessment.
8. **If all files `PROCEED`** → Validator Agent publishes `assessorflow.validation.complete` → Orchestrator proceeds to Phase 4 (Material Sufficiency Check)
9. **(Optional) Rubric processing:** If the assessor uploaded marking rubrics, the Validator Agent also validates and extracts text, then passes to Knowledge Service via `ProcessMaterial` with `source = 'rubric'` → stored in `policy_chunks` (Policy KB)

The frontend polls 2.2.4 to check validation progress during Phase 3.

**MRC internal call (used by Validator Agent as a tool):**

```
POST https://{region}-aiplatform.googleapis.com/v1/projects/{project_id}/locations/{region}/endpoints/{endpoint_id}:predict
Authorization: Bearer {gcp_access_token}
```

**Request:**
```json
{
  "instances": [
    {
      "file_name": "world_history.pdf",
      "storage_path": "gs://bucket/assessments/uuid/world_history.pdf"
    }
  ]
}
```

**Response:**
```json
{
  "predictions": [
    {
      "file_name": "world_history.pdf",
      "readiness": true,
      "confidence": 0.96
    }
  ]
}
```

| Field | Type | Notes |
|-------|------|-------|
| readiness | boolean | `true` = document is readable and parseable. `false` = blurry, corrupted, or unreadable |
| confidence | float | Model's confidence score (0.0–1.0). The Validator Agent uses this as one input for its reasoning — it does not simply apply a boolean threshold but reasons about whether the document is usable for the assessment purpose |

> The Vertex AI Endpoint URL, project_id, region, and endpoint_id are configured via environment variables. The GCP access token is obtained via Workload Identity (K8s service account). The Validator Agent calls MRC as a tool — MRC's contract is unchanged.

---

#### 2.2.4 Get Material Validation Status

**Type:** External
**Caller:** Frontend (polls for validation results)

```
GET /api/v1/assessments/{assessment_id}/materials/validation-status
```

**Response (200):**
```json
{
  "status": "complete",
  "results": {
    "uuid-1": { "file_name": "a.pdf", "readiness_status": "PROCEED" },
    "uuid-2": { "file_name": "b.pdf", "readiness_status": "TERMINATE" },
    "uuid-3": { "file_name": "c.docx", "readiness_status": "PROCEED" }
  },
  "all_passed": false,
  "failed_count": 1
}
```

---

#### 2.2.5 Start Workflow (Assessor)

**Type:** External
**Phase:** 2 → 3
**Caller:** Frontend (Assessor) → **Assessment Submission Service**
**After this call:** Assessment Submission Service publishes `assessorflow.workflow.start` → Orchestrator creates workflow → material validation begins (Phase 3)

The assessor clicks "Start Workflow" after uploading all materials and (optionally) rubrics. This is the explicit trigger that kicks off the entire assessment lifecycle. Material validation (MRC) happens inside the workflow as Phase 3.

```
POST /api/v1/assessments/{assessment_id}/start
```

**Headers:** `Authorization: Bearer {access_token}`

**Request body:** None (or `{}`)

**Preconditions:**
- `assessment_configs.status` must be `draft`
- At least one material must be uploaded for this assessment
- Assessor must own the assessment (`assessor_id` matches JWT)

**Response (202):**
```json
{
  "assessment_id": "uuid",
  "status": "material_validation",
  "message": "Workflow started. Material validation in progress."
}
```

**Side effects:**
1. `assessment_configs.status` → `material_validation`
2. Publishes `assessorflow.workflow.start` event via Pub/Sub
3. Orchestrator receives event → creates `workflows` record → begins Phase 3 (MRC)

**Errors:**
| Status | Reason |
|--------|--------|
| 400 | No materials uploaded |
| 404 | Assessment not found |
| 409 | Assessment already started (`status` is not `draft`) |

---

#### 2.2.6 Upload Web Research Materials (Internal — Web Research Agent)

**Type:** Internal (gRPC)
**Phase:** 5
**Caller:** Web Research Agent → **Assessment Submission Service**
**Triggered by:** Web Research Agent completes web search and needs to register collected content (text + images)

The Web Research Agent acts as a replacement for manual assessor uploads — it registers collected content in `assessment_materials` with `source = 'web_research'`. Text is saved as `.md` files, images are saved as-is. All files are stored in Cloud Object Storage. After registration, the Orchestrator triggers the Validator Agent to validate these materials through the same path as assessor uploads (Phase 3).

```
UploadWebResearchMaterials(materials[]) → UploadResult
```

**Request:**
```json
{
  "assessment_id": "uuid",
  "materials": [
    {
      "file_name": "web_research_oop.md",
      "file_content": "# Web Research Results\n\nSource: https://example.com/oop-guide\n\nEncapsulation is one of the four fundamental OOP concepts...",
      "file_type": "md",
      "source_url": "https://example.com/oop-guide"
    },
    {
      "file_name": "oop-diagram.png",
      "file_content_base64": "iVBORw0KGgoAAAANSUhEUg...",
      "file_type": "png",
      "source_url": "https://example.com/oop-diagram.png"
    }
  ]
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_id | string (UUID) | References `assessment_configs.id` |
| materials | array | List of collected content to register |
| file_name | string | Filename for storage (e.g. `web_research_oop.md`, `oop-diagram.png`) |
| file_content | string | Text content for `.md` files. Mutually exclusive with `file_content_base64` |
| file_content_base64 | string | Base64-encoded content for image files. Mutually exclusive with `file_content` |
| file_type | string | `md` (text), `png`, `jpg` (images) |
| source_url | string | URL where the content was retrieved. Stored in `assessment_materials.source_url` |

**Response (201):**
```json
{
  "materials_created": 2,
  "material_ids": ["uuid-1", "uuid-2"]
}
```

**Side effects:**
1. Files stored in Cloud Object Storage (permanent, for audit trail)
2. `assessment_materials` rows created with `source = 'web_research'`, `readiness_status = null`, `source_url` set
3. After Web Research Agent publishes completion, Orchestrator triggers Validator Agent — same validation path as Phase 3

---

### 2.2b Marking Rubrics

#### 2.2b.1 Upload Marking Rubric

**Type:** External (REST)
**Phase:** 2
**Caller:** Frontend (Assessor) → **Assessment Submission Service**

The assessor uploads marking rubric documents in a **separate upload area** from learning materials. These rubrics define how the assessor wants grading to be performed. The file is stored in Cloud Object Storage, validated by Material Readiness Checker, then processed by Knowledge Service (`ProcessMaterial` with `source = 'rubric'`) — chunked, embedded, and stored in `policy_chunks` (Policy KB). During Phase 10, the Evaluator Agent searches these rubric embeddings to ground its grading decisions, providing **explainability** — the UI can show exactly which rubric sections the agent used to make each grading decision.

```
POST /api/v1/assessments/{assessment_id}/rubrics
Content-Type: multipart/form-data
```

**Headers:** `Authorization: Bearer {access_token}`

**Form fields:**
- `file` — the rubric file (PDF or DOCX)

**Response (201):**
```json
{
  "id": "uuid",
  "file_name": "marking_rubric_oop.pdf",
  "file_type": "pdf",
  "storage_path": "gs://bucket/assessments/uuid/rubrics/marking_rubric_oop.pdf",
  "readiness_status": null,
  "created_at": "2026-03-21T10:10:00Z"
}
```

| Field | Type | Notes |
|-------|------|-------|
| id | string (UUID) | `assessment_rubrics.id` |
| file_name | string | Original filename |
| file_type | string | `pdf` or `docx` |
| storage_path | string | Cloud Object Storage path — stored separately from learning materials (under `/rubrics/` subfolder) |
| readiness_status | string or null | `null` immediately after upload (not yet validated). Updated to `PROCEED` or `TERMINATE` by Validator Agent during Phase 3 |

**Side effects:**
1. File stored in Cloud Object Storage
2. `assessment_rubrics` row created with `readiness_status = null`

**Note:** Rubric validation and processing does NOT happen at upload time. During Phase 3, the Validator Agent validates the rubric (readability + content safety), extracts text via OCR, and passes the text to Knowledge Service via `ProcessMaterial` with `source = 'rubric'` → stored in `policy_chunks` (Policy KB).

**Errors:**
| Status | Reason |
|--------|--------|
| 400 | Unsupported file type |
| 404 | Assessment not found |
| 413 | File too large |

---

#### 2.2b.2 List Rubrics

**Type:** External (REST)
**Phase:** 2
**Caller:** Frontend (Assessor) → **Assessment Submission Service**

```
GET /api/v1/assessments/{assessment_id}/rubrics
```

**Headers:** `Authorization: Bearer {access_token}`

**Response (200):**
```json
{
  "items": [
    {
      "id": "uuid",
      "file_name": "marking_rubric_oop.pdf",
      "file_type": "pdf",
      "storage_path": "gs://bucket/assessments/uuid/rubrics/marking_rubric_oop.pdf",
      "readiness_status": "PROCEED",
      "created_at": "2026-03-21T10:10:00Z"
    }
  ]
}
```

---

### 2.3 Question Sets (Draft — Written by Q&A Generation Agent)

> **End-to-end flow for Section 2.3:**
>
> ```
> Phase 7 (Question Generation):
>   Orchestrator ──Pub/Sub──→ Q&A Gen Agent          [assessorflow.qa-generation.trigger]
>   Q&A Gen Agent ──gRPC──→ Assessment Submission     [2.3.1 CreateQuestionSet]
>   Q&A Gen Agent ──gRPC──→ Knowledge Service         [3.2.2 SimilaritySearch]
>   Q&A Gen Agent ──gRPC──→ Assessment Submission     [2.3.2 WriteGeneratedQuestions]
>   Q&A Gen Agent ──Pub/Sub──→ Orchestrator           [assessorflow.qa-generation.complete]
>
> Phase 7a (Quality Validation — may loop):
>   Orchestrator ──Pub/Sub──→ Evaluator Agent           [assessorflow.qa-generation.complete triggers validation]
>   Evaluator Agent ──gRPC──→ Assessment Submission   [2.3.3 GetGeneratedQuestions]
>   Evaluator Agent ──gRPC──→ Knowledge Service       [3.2.4 GetChunksByIds]
>   IF PASS:
>     Evaluator Agent ──Pub/Sub──→ Orchestrator       [assessorflow.quality-validation.complete]
>   IF FAIL:
>     Evaluator Agent ──Pub/Sub──→ Orchestrator       [assessorflow.quality-validation.failed]
>     Orchestrator ──Pub/Sub──→ Q&A Gen Agent           [re-trigger qa-generation]
>     Q&A Gen Agent ──gRPC──→ Assessment Submission     [2.3.4 IncrementIteration]
>     Q&A Gen Agent ──gRPC──→ Assessment Submission     [2.3.2 WriteGeneratedQuestions (iteration 2+)]
>     ... loop back to validation
> ```

#### 2.3.1 Create Question Set

**Type:** Internal (gRPC)
**Phase:** 7
**Caller:** Q&A Generation Agent → **Assessment Submission Service**
**Triggered by:** Orchestrator publishes `assessorflow.qa-generation.trigger` (see `pubsub.md` 5.8)

The Orchestrator triggers the Q&A Generation Agent via Pub/Sub with a pre-generated `question_set_id`. The Q&A Gen Agent then calls this gRPC endpoint to create the question set record in Assessment Submission Service before generating questions.

```
CreateQuestionSet(question_set) → QuestionSet
```

**Request:**
```json
{
  "id": "uuid",
  "workflow_id": "wf_9f3a21"
}
```

| Field | Type | Notes |
|-------|------|-------|
| id | string (UUID) | Pre-generated by Orchestrator, included in the `assessorflow.qa-generation.trigger` Pub/Sub payload |
| workflow_id | string | From the same Pub/Sub payload. Originates from Orchestrator `workflows.id` |

**Response (201):**
```json
{
  "id": "uuid",
  "workflow_id": "wf_9f3a21",
  "iteration_count": 0,
  "status": "generated",
  "created_at": "2026-03-21T11:00:00Z"
}
```

---

#### 2.3.2 Write Generated Questions (Batch)

**Type:** Internal (gRPC)
**Phase:** 7
**Caller:** Q&A Generation Agent → **Assessment Submission Service**
**Preceded by:** Q&A Gen Agent retrieves topics from Knowledge Service (3.1.2) and document chunks via similarity search (3.2.2)

After retrieving classified topics and relevant document chunks, the Q&A Gen Agent generates questions + model answers and writes them to Assessment Submission Service in batch. This is called once per iteration — if the Evaluator Agent rejects (Phase 7a), the Q&A Gen Agent calls 2.3.4 to increment the iteration, then calls this endpoint again with the new iteration number.

```
WriteGeneratedQuestions(question_set_id, questions[]) → WriteResult
```

**Request:**
```json
{
  "question_set_id": "uuid",
  "iteration": 1,
  "questions": [
    {
      "question_type": "structured",
      "content": "Which OOP concept involves bundling data with the methods that operate on it?",
      "structured_answer": "A",
      "non_structured_model_answer": null,
      "metadata": {
        "options": {
          "A": "Encapsulation",
          "B": "Polymorphism",
          "C": "Compilation",
          "D": "Iteration"
        },
        "source_chunk_ids": ["chunk_101", "chunk_205"],
        "difficulty": "easy",
        "topic": "Object-Oriented Programming"
      },
      "topic_id": "uuid"
    },
    {
      "question_type": "non_structured",
      "content": "Explain the key differences between encapsulation and abstraction with examples.",
      "structured_answer": null,
      "non_structured_model_answer": "Encapsulation bundles data with methods and restricts direct access via access modifiers. Abstraction hides complexity by exposing only essential features through interfaces...",
      "metadata": {
        "rubric": "Award marks for identifying at least 2 key differences with examples.",
        "max_marks": 10,
        "source_chunk_ids": ["chunk_101", "chunk_302"],
        "difficulty": "medium",
        "topic": "Object-Oriented Programming"
      },
      "topic_id": "uuid"
    }
  ]
}
```

**Response (201):**
```json
{
  "question_set_id": "uuid",
  "questions_written": 8,
  "iteration": 1
}
```

**After this call:** Q&A Gen Agent publishes `assessorflow.qa-generation.complete` to Orchestrator (see `pubsub.md` 5.9).

---

#### 2.3.3 Get Generated Questions for Review (External — REST)

**Type:** External (REST)
**Phase:** 8
**Caller:** Frontend → **Assessment Submission Service**
**Triggered by:** Assessor clicks review link from email. This endpoint is used by the assessor review page (2.4.1 also references this data).

The assessor reviews generated questions for quality. Answers are **not** shown — the assessor only sees the question content and metadata.

```
GET /api/v1/assessments/{assessment_id}/generated-questions?question_set_id={uuid}
```

**Headers:** `Authorization: Bearer {access_token}`

**Response (200):**
```json
{
  "question_set_id": "uuid",
  "iteration": 1,
  "status": "under_review",
  "questions": [
    {
      "id": "uuid",
      "question_type": "structured",
      "content": "Which OOP concept involves bundling data with the methods that operate on it?",
      "metadata": {
        "options": {
          "A": "Encapsulation",
          "B": "Polymorphism",
          "C": "Compilation",
          "D": "Iteration"
        },
        "difficulty": "easy",
        "topic": "Object-Oriented Programming"
      },
      "topic_id": "uuid",
      "was_approved": null,
      "grounding_citations": [
        {
          "chunk_id": "chunk_101",
          "source_file": "world_history.pdf",
          "page": 2,
          "text_snippet": "Encapsulation is one of the four fundamental OOP concepts..."
        }
      ]
    }
  ]
}
```

**Note:** `structured_answer` and `non_structured_model_answer` are **stripped** — the assessor reviews question quality, not answers. `grounding_citations` are included so the assessor can trace each question back to source material.

---

#### 2.3.3b Get Generated Questions with Answers (Internal — gRPC)

**Type:** Internal (gRPC)
**Phase:** 7a
**Caller:** Evaluator Agent → **Assessment Submission Service**
**Triggered by:** Orchestrator triggers Evaluator Agent after receiving `assessorflow.qa-generation.complete`

The Evaluator Agent retrieves generated questions **with model answers** for quality validation — checking whether questions are grounded in source material, model answers are accurate, and difficulty matches the config.

```
GetGeneratedQuestionsWithAnswers(question_set_id, iteration?) → Questions[]
```

**Request:**
```json
{
  "question_set_id": "uuid",
  "iteration": 1
}
```

**Note:** If `iteration` is omitted, returns the latest iteration.

**Response:**
```json
{
  "question_set_id": "uuid",
  "iteration": 1,
  "status": "generated",
  "questions": [
    {
      "id": "uuid",
      "question_type": "structured",
      "content": "Which OOP concept involves bundling data with the methods that operate on it?",
      "structured_answer": "A",
      "non_structured_model_answer": null,
      "metadata": {
        "options": {
          "A": "Encapsulation",
          "B": "Polymorphism",
          "C": "Compilation",
          "D": "Iteration"
        },
        "source_chunk_ids": ["chunk_101", "chunk_205"],
        "difficulty": "easy",
        "topic": "Object-Oriented Programming"
      },
      "topic_id": "uuid",
      "was_approved": null
    }
  ]
}
```

---

#### 2.3.4 Update Question Set (Iteration Increment)

**Type:** Internal (gRPC)
**Phase:** 7a (feedback loop)
**Caller:** Q&A Generation Agent → **Assessment Submission Service**
**Triggered by:** Orchestrator re-triggers Q&A Gen Agent after Evaluator Agent publishes `assessorflow.quality-validation.failed` (see `pubsub.md` 5.11)

Called when the Evaluator Agent rejects the generated Q&A. The Q&A Gen Agent increments the iteration count before generating a new set. The previous iteration's questions remain in `generated_questions` for audit trail. After incrementing, the Q&A Gen Agent calls 2.3.2 again with the new iteration number.

```
IncrementQuestionSetIteration(question_set_id) → QuestionSet
```

**Request:**
```json
{
  "question_set_id": "uuid"
}
```

**Response (200):**
```json
{
  "id": "uuid",
  "iteration_count": 2,
  "status": "generated",
  "updated_at": "2026-03-21T11:30:00Z"
}
```

**After this call:** Q&A Gen Agent generates new questions → calls 2.3.2 with `iteration = 2` → publishes `assessorflow.qa-generation.complete` → Evaluator Agent validates again → loop until pass.

---

### 2.4 Approved Questions (HITL — Phase 8)

> **End-to-end flow for Section 2.4:**
>
> ```
> Phase 8 (Human-in-the-Loop Review):
>   Orchestrator ──Pub/Sub──→ Email Service              [assessorflow.email.request.assessor-review]
>   Email Service ──email──→ Assessor                    [review link in email]
>   Assessor ──Frontend──→ Assessment Submission          [2.4.1 Get Questions for Review]
>   Assessor ──Frontend──→ Assessment Submission          [2.4.2 Approve Question Set]
>   Assessment Submission ──Pub/Sub──→ Orchestrator       [assessorflow.human-review.approved]
> ```

#### 2.4.1 Get Questions for Assessor Review

**Type:** External
**Phase:** 8
**Caller:** Frontend → **Assessment Submission Service**
**Triggered by:** Assessor clicks review link from email sent by Email Service (via `assessorflow.email.request.assessor-review`)

```
GET /api/v1/assessments/{assessment_id}/review
```

**Headers:** `Authorization: Bearer {access_token}`

**Response (200):**
```json
{
  "assessment_id": "uuid",
  "question_set_id": "uuid",
  "status": "under_review",
  "questions": [
    {
      "id": "uuid",
      "question_type": "structured",
      "content": "Which OOP concept involves bundling data with the methods that operate on it?",
      "metadata": {
        "options": {
          "A": "Encapsulation",
          "B": "Polymorphism",
          "C": "Compilation",
          "D": "Iteration"
        },
        "option_explanations": {
          "A": { "text": "Encapsulation", "explanation": "Correct. Encapsulation bundles data with the methods that operate on it.", "is_correct": true },
          "B": { "text": "Polymorphism", "explanation": "Incorrect. Polymorphism is about different classes responding to the same method call.", "is_correct": false },
          "C": { "text": "Compilation", "explanation": "Incorrect. Compilation is not an OOP concept.", "is_correct": false },
          "D": { "text": "Iteration", "explanation": "Incorrect. Iteration is a loop concept, not an OOP principle.", "is_correct": false }
        },
        "difficulty": "easy",
        "topic": "Object-Oriented Programming"
      },
      "grounding_citations": [
        {
          "chunk_id": "chunk_101",
          "source_file": "world_history.pdf",
          "page": 2,
          "text_snippet": "Encapsulation is one of the four fundamental OOP concepts. It bundles data with the methods that..."
        },
        {
          "chunk_id": "chunk_205",
          "source_file": "world_history.pdf",
          "page": 5,
          "text_snippet": "The four pillars of OOP — encapsulation, abstraction, inheritance, and polymorphism — form the..."
        }
      ]
    }
  ]
}
```

| Field | Type | Notes |
|-------|------|-------|
| grounding_citations | array | Source document chunks this question was generated from. Resolved from `metadata.source_chunk_ids` — Assessment Submission Service calls Knowledge Service (`GetChunksByIds` 3.2.4) to fetch chunk content |
| chunk_id | string | References `document_chunks.id` |
| source_file | string | Original filename the chunk came from |
| page | integer | Page number in the source file |
| text_snippet | string | First ~200 characters of the chunk content — gives the assessor a preview without showing the full chunk |

**Note:** `structured_answer` and `non_structured_model_answer` are **not** returned to the assessor review UI — the assessor reviews question quality, not answers. `grounding_citations` allows the assessor to verify that each question is traceable to the uploaded source material ("Trace to Source").

---

#### 2.4.2 Approve Question Set

**Type:** External
**Phase:** 8
**Caller:** Frontend → **Assessment Submission Service**
**After this call:** Assessment Submission Service publishes `assessorflow.human-review.approved` → Orchestrator transitions to Phase 9

```
POST /api/v1/assessments/{assessment_id}/review/approve
```

**Headers:** `Authorization: Bearer {access_token}`

**Request:**
```json
{
  "removed_question_ids": ["uuid-3", "uuid-7"]
}
```

**Note:** `removed_question_ids` lists the questions the assessor wants to remove. All other questions are approved.

**Response (200):**
```json
{
  "approved_question_set_id": "uuid",
  "original_question_set_id": "uuid",
  "questions_approved": 6,
  "questions_removed": 2,
  "approved_at": "2026-03-21T12:00:00Z"
}
```

**Side effects:**
1. Creates `approved_question_sets` record with `original_question_set_id` linking to draft
2. Copies kept questions to `approved_questions`
3. Marks removed questions in `generated_questions` as `was_approved = false`
4. Marks kept questions as `was_approved = true`
5. Updates `question_sets.status` → `approved`
6. Publishes `assessorflow.human-review.approved` event

---

#### 2.4.3 Get Approved Questions (External — REST)

**Type:** External (REST)
**Phase:** 8 (assessor views final approved set)
**Caller:** Frontend → **Assessment Submission Service**

```
GET /api/v1/assessments/{assessment_id}/approved-questions
```

**Headers:** `Authorization: Bearer {access_token}`

**Response (200):**
```json
{
  "approved_question_set_id": "uuid",
  "questions": [
    {
      "id": "uuid",
      "question_type": "structured",
      "content": "Which OOP concept involves bundling data with the methods that operate on it?",
      "metadata": {
        "options": {
          "A": "Encapsulation",
          "B": "Polymorphism",
          "C": "Compilation",
          "D": "Iteration"
        },
        "difficulty": "easy",
        "topic": "Object-Oriented Programming"
      },
      "sort_order": 1
    }
  ]
}
```

**Note:** The REST endpoint for the assessor does NOT include `structured_answer` or `non_structured_model_answer` — it shows the approved question list for viewing purposes. Participants use a separate endpoint (2.5.2) that also strips answers.

---

#### 2.4.3b Get Approved Questions with Answers (Internal — gRPC)

**Type:** Internal (gRPC)
**Phase:** 10, 11
**Caller:** Evaluator Agent → **Assessment Submission Service** (Phase 10 — needs answers for grading), Reporting Agent → **Assessment Submission Service** (Phase 11 — needs answers + questions for feedback generation)

Agents need the full data including `structured_answer`, `non_structured_model_answer`, and `source_chunk_ids` for grading and report generation. This is the gRPC contract that returns everything.

```
GetApprovedQuestionsWithAnswers(assessment_id) → ApprovedQuestions[]
```

**Request:**
```json
{
  "assessment_id": "uuid"
}
```

**Response:**
```json
{
  "approved_question_set_id": "uuid",
  "questions": [
    {
      "id": "uuid",
      "question_type": "structured",
      "content": "Which OOP concept involves bundling data with the methods that operate on it?",
      "structured_answer": "A",
      "non_structured_model_answer": null,
      "metadata": {
        "options": {
          "A": "Encapsulation",
          "B": "Polymorphism",
          "C": "Compilation",
          "D": "Iteration"
        },
        "source_chunk_ids": ["chunk_101", "chunk_205"],
        "difficulty": "easy",
        "topic": "Object-Oriented Programming"
      },
      "sort_order": 1
    }
  ]
}
```

---

#### 2.4.4 Get Extracted Topics (External — REST)

**Type:** External (REST)
**Phase:** 8
**Caller:** Frontend → **Assessment Submission Service**
**Purpose:** Frontend displays extracted subtopics in the HITL review sidebar (qa_review.html). Each subtopic shows how many questions were generated for it. Assessment Submission Service resolves `workflow_id` from `assessment_id`, fetches topics from Knowledge Service internally, and joins with `generated_questions.topic_id` to compute question counts.

```
GET /api/v1/assessments/{assessment_id}/topics
```

**Headers:** `Authorization: Bearer {access_token}`

**Response (200):**
```json
{
  "assessment_id": "uuid",
  "subtopics": [
    { "id": "uuid", "name": "Verb Tenses", "question_count": 4 },
    { "id": "uuid", "name": "Articles (A/An/The)", "question_count": 3 },
    { "id": "uuid", "name": "Subject-Verb Agreement", "question_count": 2 },
    { "id": "uuid", "name": "Reading Comprehension", "question_count": 1 }
  ],
  "total_subtopics": 4
}
```

| Field | Type | Notes |
|-------|------|-------|
| subtopics | array | Extracted subtopics from Classification Agent (Phase 6). Each includes question count from `generated_questions` |
| subtopics[].id | string (UUID) | References `topics.id` in Knowledge Service |
| subtopics[].name | string | Subtopic name (e.g. "Verb Tenses") |
| subtopics[].question_count | integer | Number of questions generated for this subtopic. Derived from `generated_questions` where `topic_id` matches |
| total_subtopics | integer | Total number of subtopics |

---

#### 2.4.5 Send Invitations (Assessor — selective)

**Type:** External (REST)
**Phase:** 8 → 9 (`ready_for_distribution` → `assessment_active`)
**Caller:** Frontend → **Assessment Submission Service**
**After this call:** Assessment Submission Service publishes `assessorflow.email.request.assessment-link` per selected participant → Email Service sends invitation emails → `assessment_configs.status` → `assessment_active`, `workflows.current_phase` → `assessment_active`

The assessor selects which participants to invite from the distribution queue screen (distribution_queue.html). This is a one-time action — the assessor cannot send invitations again after this call.

```
POST /api/v1/assessments/{assessment_id}/invite
```

**Headers:** `Authorization: Bearer {access_token}`

**Request:**
```json
{
  "participant_ids": ["uuid-1", "uuid-2", "uuid-3", "uuid-4"]
}
```

| Field | Type | Notes |
|-------|------|-------|
| participant_ids | array of UUIDs | References `assessment_participants.id`. Must include at least one participant. All listed participants receive invitation emails |

**Preconditions:**
- `assessment_configs.status` must be `ready_for_distribution`
- All `participant_ids` must belong to this assessment
- Assessment must have approved questions (`approved_question_sets` exists)

**Response (202):**
```json
{
  "assessment_id": "uuid",
  "invitations_sent": 4,
  "status": "assessment_active"
}
```

**Side effects:**
1. Publishes `assessorflow.email.request.assessment-link` per selected participant → Email Service sends emails
2. Updates `assessment_participants.invitation_status` → `sent` for selected participants
3. `assessment_configs.status` → `assessment_active`
4. `workflows.current_phase` → `assessment_active`

**Errors:**
| Status | Reason |
|--------|--------|
| 400 | No participants specified or invalid participant IDs |
| 404 | Assessment not found |
| 409 | Invitations already sent (`status` is not `ready_for_distribution`) |

---

### 2.5 Participant Assessment (Phase 9)

> **End-to-end flow for Section 2.5:**
>
> ```
> Phase 9 (Participant Assessment):
>   Orchestrator ──Pub/Sub──→ Email Service              [assessorflow.email.request.assessment-link]
>   Email Service ──email──→ Participant                  [assessment link with signed token]
>   Participant ──Frontend──→ Assessment Submission        [2.5.1 Access Assessment]
>   Assessment Submission ──Redis──→ create session        [participant:{email} with TTL]
>   Participant ──Frontend──→ Assessment Submission        [2.5.2 Get Questions]
>   Participant ──Frontend──→ Assessment Submission        [2.5.3 Submit Assessment]
>   Assessment Submission ──Pub/Sub──→ Orchestrator        [assessorflow.participant.submission-completed]
> ```

#### 2.5.1 Access Assessment (Participant — via signed link)

**Type:** External
**Phase:** 9
**Caller:** Frontend → **Assessment Submission Service**
**Triggered by:** Participant clicks assessment link from email sent by Email Service (via `assessorflow.email.request.assessment-link`)

```
GET /api/v1/participate/{assessment_id}?token={signed_token}
```

**Note:** `signed_token` encodes `participant_id` + `assessment_id` + expiry. No login required.

**Response (200):**
```json
{
  "assessment_id": "uuid",
  "assessment_title": "OOP Mid-Term Quiz",
  "purpose": "topic_revision",
  "participant_id": "uuid",
  "participant_email": "student1@email.com",
  "duration_minutes": 60,
  "deadline": "2026-03-25T23:59:00Z",
  "structured_question_count": 6,
  "non_structured_question_count": 2,
  "total_questions": 8,
  "status": "ready"
}
```

**Validation:**
- Token signature is valid
- Assessment deadline has not passed
- Participant has not already submitted
- `invitation_status` is not already `accepted`

**Side effects:**
- Creates `participant:{email}` session in Redis (TTL = `duration_minutes + 15 min`)
- Creates `participant_submissions` record with `status = 'in_progress'`, `started_at = now()`
- Updates `assessment_participants.invitation_status` → `accepted`

**Errors:**
| Status | Reason |
|--------|--------|
| 400 | Token invalid or expired |
| 403 | Assessment deadline passed |
| 409 | Participant already submitted |

---

#### 2.5.2 Get Assessment Questions (Participant)

**Type:** External
**Phase:** 9
**Caller:** Frontend → **Assessment Submission Service**
**Preceded by:** 2.5.1 (participant session created in Redis)

```
GET /api/v1/participate/{assessment_id}/questions?token={signed_token}
```

**Response (200):**
```json
{
  "assessment_id": "uuid",
  "time_remaining_seconds": 3420,
  "questions": [
    {
      "id": "uuid",
      "question_type": "structured",
      "content": "Which OOP concept involves bundling data with the methods that operate on it?",
      "sort_order": 1,
      "options": {
        "A": "Encapsulation",
        "B": "Polymorphism",
        "C": "Compilation",
        "D": "Iteration"
      }
    },
    {
      "id": "uuid",
      "question_type": "non_structured",
      "content": "Explain the key differences between encapsulation and abstraction with examples.",
      "sort_order": 7
    }
  ]
}
```

**Note:** No `structured_answer`, no `non_structured_model_answer`, no `source_chunk_ids` — participants only see the question and options. `time_remaining_seconds` is calculated from Redis session TTL.

---

#### 2.5.3 Submit Assessment (Participant)

**Type:** External
**Phase:** 9
**Caller:** Frontend → **Assessment Submission Service**
**After this call:** Assessment Submission Service publishes `assessorflow.participant.submission-completed` → Orchestrator triggers Evaluator Agent (Phase 10)

```
POST /api/v1/participate/{assessment_id}/submit?token={signed_token}
```

**Request:**
```json
{
  "answers": [
    {
      "question_id": "uuid",
      "answer_content": "A"
    },
    {
      "question_id": "uuid",
      "answer_content": "Encapsulation bundles data with methods and uses access modifiers to restrict direct access. Abstraction hides implementation complexity..."
    }
  ]
}
```

**Response (200):**
```json
{
  "submission_id": "uuid",
  "status": "submitted",
  "submitted_at": "2026-03-21T14:55:00Z",
  "answers_recorded": 8
}
```

**Side effects:**
1. Writes `participant_answers` rows
2. Updates `participant_submissions.status` → `submitted`, sets `submitted_at`
3. Evicts `participant:{email}` from Redis
4. Publishes `assessorflow.participant.submission-completed` event

**Errors:**
| Status | Reason |
|--------|--------|
| 400 | Token invalid |
| 403 | Time expired (Redis session gone) |
| 409 | Already submitted |

---

### 2.6 Evaluations (Written by Evaluator Agent — Phase 10)

> **End-to-end flow for Section 2.6:**
>
> ```
> Phase 10 (Participant Evaluation):
>   Orchestrator ──Pub/Sub──→ Evaluator Agent            [assessorflow.evaluation.trigger]
>   Evaluator Agent ──gRPC──→ Assessment Submission       [2.4.3b GetApprovedQuestionsWithAnswers]
>   Evaluator Agent ──gRPC──→ Knowledge Service           [3.2.4 GetChunksByIds (source document chunks for grounding)]
>   Evaluator Agent ──gRPC──→ Knowledge Service           [3.3.2 SearchPolicies (system defaults + assessor rubric)]
>   Evaluator Agent grades using: model answers + source chunks + rubric policies
>   Evaluator Agent records rubric-grounded reasoning in evaluation_audit_log (explainability)
>   IF has_non_structured:
>     Evaluator Agent ──gRPC──→ Assessment Submission     [2.6.2 CreateGroupEvaluation (once per group per question)]
>   Evaluator Agent ──gRPC──→ Assessment Submission       [2.6.1 CreateEvaluation (per participant)]
>   Evaluator Agent ──gRPC──→ Decision Audit L-11         [4.3 LogEvaluationAudit (fire-and-forget, includes rubric references)]
>   Evaluator Agent ──Pub/Sub──→ Orchestrator             [assessorflow.evaluation.complete]
> ```

#### 2.6.1 Create Evaluation

**Type:** Internal (gRPC)
**Phase:** 10
**Caller:** Evaluator Agent → **Assessment Submission Service**
**Triggered by:** Orchestrator publishes `assessorflow.evaluation.trigger` (see `pubsub.md` 5.14)
**After this call:** Evaluator Agent publishes `assessorflow.evaluation.complete` → Orchestrator increments `evaluations_completed` and checks completeness

```
CreateEvaluation(evaluation) → Evaluation
```

**Request:**
```json
{
  "workflow_id": "wf_9f3a21",
  "participant_id": "uuid",
  "submission_id": "uuid",
  "total_score": 85.5,
  "max_score": 100.0,
  "details": [
    {
      "question_id": "uuid",
      "score": 10.0,
      "max_score": 10.0,
      "reasoning": null,
      "evaluation_method": "deterministic",
      "group_evaluation_id": null
    },
    {
      "question_id": "uuid",
      "score": 15.0,
      "max_score": 20.0,
      "reasoning": "Good identification of two differences. Example of abstraction was incomplete...",
      "evaluation_method": "llm_based",
      "group_evaluation_id": "uuid"
    }
  ]
}
```

**Response (201):**
```json
{
  "id": "uuid",
  "status": "completed",
  "created_at": "2026-03-21T15:00:00Z"
}
```

**Side effect:** Updates `participant_submissions.status` → `evaluated`.

---

#### 2.6.2 Create Group Evaluation (Idempotent)

**Type:** Internal (gRPC)
**Phase:** 10
**Caller:** Evaluator Agent → **Assessment Submission Service**
**Preceded by:** Orchestrator waits until all group members submit before triggering evaluation for non-structured questions

**Idempotent:** The Orchestrator sends one evaluation trigger per participant. For a 4-member group, the Evaluator gets 4 triggers with the same `group_id`. This endpoint is idempotent on `(group_id, question_id)` — if a group evaluation already exists for this combination, the existing record is returned (`200 OK`) instead of creating a duplicate. The first trigger to call this endpoint creates the record; subsequent triggers for the same group reuse it.

```
CreateGroupEvaluation(group_evaluation) → GroupEvaluation
```

**Request:**
```json
{
  "workflow_id": "wf_9f3a21",
  "group_id": "uuid",
  "question_id": "uuid",
  "group_score": 15.0,
  "max_score": 20.0,
  "reasoning": "Group demonstrated strong understanding of..."
}
```

**Response (201 Created — new):**
```json
{
  "id": "uuid",
  "created": true,
  "created_at": "2026-03-21T15:00:00Z"
}
```

**Response (200 OK — already exists):**
```json
{
  "id": "uuid",
  "created": false,
  "created_at": "2026-03-21T15:00:00Z"
}
```

**Note:** This must be called BEFORE individual `evaluation_details` that reference it via `group_evaluation_id`. The `created` flag tells the Evaluator Agent whether it was the first to evaluate this group question — useful for logging.

---

#### 2.6.3 Get Group Member Submissions

**Type:** Internal (gRPC)
**Phase:** 10
**Caller:** Evaluator Agent → **Assessment Submission Service**
**Purpose:** For non-structured (essay) questions evaluated at group level, the Evaluator Agent needs to read ALL group members' answers for a specific question to evaluate them collectively. This endpoint returns all participant answers for a given group + question combination.

```
GetGroupMemberSubmissions(group_id, question_id) → GroupSubmissions
```

**Request:**
```json
{
  "group_id": "uuid",
  "question_id": "uuid"
}
```

**Response:**
```json
{
  "group_id": "uuid",
  "group_name": "Group A",
  "question_id": "uuid",
  "question_text": "Explain the key differences between encapsulation and abstraction with examples.",
  "submissions": [
    {
      "participant_id": "uuid",
      "participant_email": "alice@email.com",
      "answer_content": "Encapsulation bundles data with methods and uses access modifiers..."
    },
    {
      "participant_id": "uuid",
      "participant_email": "bob@email.com",
      "answer_content": "Abstraction hides implementation details while encapsulation restricts access..."
    }
  ]
}
```

| Field | Type | Notes |
|-------|------|-------|
| group_id | string (UUID) | References `participant_groups.id` |
| group_name | string | Group display name (e.g. "Group A") |
| question_id | string (UUID) | References `approved_questions.id` — the non-structured question being evaluated |
| question_text | string | The question content — included for convenience so the Evaluator doesn't need a separate call |
| submissions | array | All group members' answers for this question |
| submissions[].participant_id | string (UUID) | References `assessment_participants.id` |
| submissions[].participant_email | string | Participant email for identification |
| submissions[].answer_content | string | The participant's answer text from `participant_answers.answer_content` |

---

#### 2.6.4 Get Evaluation (for Reporting Agent)

**Type:** Internal (gRPC)
**Phase:** 11
**Caller:** Reporting Agent → **Assessment Submission Service**
**Triggered by:** Orchestrator publishes `assessorflow.reporting.trigger` with `evaluation_id` in payload (see `pubsub.md` 5.16)

```
GetEvaluation(evaluation_id) → EvaluationWithDetails
```

**Response:**
```json
{
  "id": "uuid",
  "workflow_id": "wf_9f3a21",
  "participant_id": "uuid",
  "submission_id": "uuid",
  "total_score": 85.5,
  "max_score": 100.0,
  "status": "completed",
  "details": [
    {
      "id": "uuid",
      "question_id": "uuid",
      "score": 10.0,
      "max_score": 10.0,
      "reasoning": null,
      "evaluation_method": "deterministic",
      "group_evaluation_id": null
    }
  ]
}
```

---

### 2.7 Reports (Written by Reporting Agent — Phase 11)

> **End-to-end flow for Section 2.7:**
>
> ```
> Phase 11 (Reporting):
>   Orchestrator ──Pub/Sub──→ Reporting Agent              [assessorflow.reporting.trigger]
>   Reporting Agent ──gRPC──→ Assessment Submission         [2.4.3 GetApprovedQuestions]
>   Reporting Agent ──gRPC──→ Assessment Submission         [2.6.4 GetEvaluation]
>   Reporting Agent generates per-question feedback + overall summary (LLM)
>   Reporting Agent ──gRPC──→ Assessment Submission         [2.7.1 CreateReport]
>   Reporting Agent ──gRPC──→ Decision Audit L-11           [4.1 LogDecision (fire-and-forget)]
>   Reporting Agent ──Pub/Sub──→ Orchestrator               [assessorflow.reporting.complete]
>
> Phase 12 (Workflow Completion — after all reports done):
>   Orchestrator ──Pub/Sub──→ Email Service                 [assessorflow.email.request.participant-report]
>   Participant ──Frontend──→ Assessment Submission          [2.7.2 Get Report]
>   Assessor ──Frontend──→ Assessment Submission             [2.7.3 Get All Reports]
> ```

#### 2.7.1 Create Report

**Type:** Internal (gRPC)
**Phase:** 11
**Caller:** Reporting Agent → **Assessment Submission Service**
**Triggered by:** Orchestrator publishes `assessorflow.reporting.trigger` (see `pubsub.md` 5.16)
**After this call:** Reporting Agent publishes `assessorflow.reporting.complete` → Orchestrator increments `reports_completed` and checks completeness

```
CreateReport(report) → Report
```

**Request:**
```json
{
  "workflow_id": "wf_9f3a21",
  "participant_id": "uuid",
  "evaluation_id": "uuid",
  "report_content": {
    "total_score": 85,
    "max_score": 100,
    "per_question_feedback": [
      {
        "question_id": "uuid",
        "question_type": "structured",
        "score": 10,
        "max_score": 10,
        "feedback": "Correct. Polymorphism allows objects of different classes to respond to the same method call."
      }
    ],
    "overall_summary": "You scored 85/100. Strong understanding of core OOP concepts..."
  }
}
```

**Response (201):**
```json
{
  "id": "uuid",
  "status": "completed",
  "generated_at": "2026-03-21T16:00:00Z"
}
```

**Side effect:** Updates `participant_submissions.status` → `reported`.

---

#### 2.7.2 Get Report (Participant — with per-question detail)

**Type:** External
**Phase:** 12 (after assessor distributes reports)
**Caller:** Frontend → **Assessment Submission Service**
**Triggered by:** Participant clicks report link from email sent by Email Service (via `assessorflow.email.request.participant-report`)

Same per-question detail as the assessor view (2.7.3), but scoped to a single participant. The participant sees their own answers, scores, feedback, and source traceability.

```
GET /api/v1/reports/{report_id}?token={signed_token}
```

**Response (200):**
```json
{
  "id": "uuid",
  "participant_email": "student1@email.com",
  "assessment_title": "OOP Mid-Term Quiz",
  "assessment_purpose": "topic_revision",
  "overall_score": 85,
  "max_score": 100,
  "question_evaluations": [
    {
      "question_id": "uuid",
      "question_type": "structured",
      "question_text": "Which OOP concept involves bundling data with the methods that operate on it?",
      "score": 10,
      "max_score": 10,
      "participant_answer": "A",
      "correct_answer": "A",
      "is_correct": true,
      "feedback": "Correct. Encapsulation bundles data with the methods that operate on it.",
      "option_explanations": {
        "A": { "text": "Encapsulation", "explanation": "Correct. Encapsulation bundles data with the methods that operate on it.", "is_correct": true },
        "B": { "text": "Polymorphism", "explanation": "Incorrect. Polymorphism is about different classes responding to the same method call.", "is_correct": false },
        "C": { "text": "Compilation", "explanation": "Incorrect. Compilation is not an OOP concept.", "is_correct": false },
        "D": { "text": "Iteration", "explanation": "Incorrect. Iteration is a loop concept, not an OOP principle.", "is_correct": false }
      },
      "grounding_citations": [
        {
          "chunk_id": "chunk_101",
          "source_file": "world_history.pdf",
          "page": 2,
          "text_snippet": "Encapsulation is one of the four fundamental OOP concepts..."
        }
      ]
    },
    {
      "question_id": "uuid",
      "question_type": "non_structured",
      "question_text": "Explain the key differences between encapsulation and abstraction with examples.",
      "score": 15,
      "max_score": 20,
      "participant_answer": "Encapsulation bundles data with methods...",
      "feedback": "Good identification of two differences. However, your example of abstraction was incomplete — you described hiding implementation but did not mention interface exposure.",
      "reasoning": "Compared against model answer and source material. 2 of 3 key differences identified. Missing: interface exposure aspect of abstraction.",
      "rubric_match": {
        "source_file": "marking_rubric_oop.pdf",
        "section": "Comprehension Questions",
        "text": "Award marks for identifying at least 2 key differences with examples from the source material."
      },
      "grounding_citations": [
        {
          "chunk_id": "chunk_101",
          "source_file": "world_history.pdf",
          "page": 2,
          "text_snippet": "Encapsulation is one of the four fundamental OOP concepts..."
        }
      ]
    }
  ],
  "summary_narrative": "You scored 85/100. Strong understanding of core OOP concepts (polymorphism, inheritance). Areas for improvement: encapsulation vs abstraction distinction.",
  "strengths": ["Polymorphism", "Inheritance", "Class design"],
  "areas_for_improvement": "Encapsulation vs abstraction distinction — review Chapter 3 of the source material for detailed examples."
}
```

---

#### 2.7.3 Get Reports (Assessor — all participants with per-question detail)

**Type:** External
**Caller:** Frontend → **Assessment Submission Service**
**Phase:** 12 (`report_review` — assessor reviews reports before distributing)

The assessor views detailed evaluation reports for all participants. Each report includes per-question breakdown with participant answer vs model answer, scores, AI reasoning, rubric match, and grounding citations. This is the data the assessor reviews before clicking "Send Reports" (2.7.4).

```
GET /api/v1/assessments/{assessment_id}/reports
```

**Headers:** `Authorization: Bearer {access_token}`

**Response (200):**
```json
{
  "assessment_id": "uuid",
  "reports": [
    {
      "id": "uuid",
      "participant_id": "uuid",
      "participant_email": "student1@email.com",
      "overall_score": 85,
      "max_score": 100,
      "group_label": "Group A",
      "status": "completed",
      "generated_at": "2026-03-21T16:00:00Z",
      "question_evaluations": [
        {
          "question_id": "uuid",
          "question_type": "structured",
          "question_text": "Which OOP concept involves bundling data with the methods that operate on it?",
          "score": 10,
          "max_score": 10,
          "participant_answer": "A",
          "model_answer": "A",
          "is_correct": true,
          "option_explanations": {
            "A": { "text": "Encapsulation", "explanation": "Correct. Encapsulation bundles data with the methods that operate on it.", "is_correct": true },
            "B": { "text": "Polymorphism", "explanation": "Incorrect. Polymorphism is about different classes responding to the same method call.", "is_correct": false },
            "C": { "text": "Compilation", "explanation": "Incorrect. Compilation is not an OOP concept.", "is_correct": false },
            "D": { "text": "Iteration", "explanation": "Incorrect. Iteration is a loop concept, not an OOP principle.", "is_correct": false }
          },
          "grounding_citations": [
            {
              "chunk_id": "chunk_101",
              "source_file": "world_history.pdf",
              "page": 2,
              "text_snippet": "Encapsulation is one of the four fundamental OOP concepts..."
            }
          ]
        },
        {
          "question_id": "uuid",
          "question_type": "non_structured",
          "question_text": "Explain the key differences between encapsulation and abstraction with examples.",
          "score": 15,
          "max_score": 20,
          "participant_answer": "Encapsulation bundles data with methods...",
          "model_answer": "Encapsulation bundles data with methods and restricts direct access via access modifiers. Abstraction hides complexity...",
          "reasoning": "Good identification of two differences. Example of abstraction was incomplete — described hiding implementation but did not mention interface exposure.",
          "rubric_match": {
            "source_file": "marking_rubric_oop.pdf",
            "section": "Comprehension Questions",
            "text": "Award marks for identifying at least 2 key differences with examples from the source material."
          },
          "grounding_citations": [
            {
              "chunk_id": "chunk_101",
              "source_file": "world_history.pdf",
              "page": 2,
              "text_snippet": "Encapsulation is one of the four fundamental OOP concepts..."
            }
          ]
        }
      ],
      "summary_narrative": "You scored 85/100. Strong understanding of core OOP concepts (polymorphism, inheritance). Areas for improvement: encapsulation vs abstraction distinction.",
      "strengths": ["Polymorphism", "Inheritance", "Class design"],
      "areas_for_improvement": "Encapsulation vs abstraction distinction — review Chapter 3 of the source material for detailed examples."
    }
  ]
}
```

| Field | Type | Notes |
|-------|------|-------|
| group_label | string or null | Group name if participant belongs to a group. NULL for assessments with structured questions only |
| question_evaluations | array | Per-question breakdown from `evaluation_details` + `approved_questions` + `participant_answers` |
| is_correct | boolean | MCQ only — whether participant selected the correct option |
| reasoning | string | Non-structured only — AI evaluation reasoning from `evaluation_details.reasoning` |
| rubric_match | object or null | Non-structured only — which rubric section was used for grading (from `evaluation_audit_log.grounding_sources`). NULL if no assessor rubric uploaded |
| grounding_citations | array | Source document chunks the question was generated from |
| summary_narrative | string | Overall summary from `participant_reports.report_content.overall_summary` |
| strengths | array of strings | From Reporting Agent's analysis |
| areas_for_improvement | string | From Reporting Agent's analysis |

---

#### 2.7.4 Distribute Reports (Assessor — trigger email distribution)

**Type:** External
**Caller:** Frontend → **Assessment Submission Service**
**Phase:** 12 (`report_review` → `completed`)
**After this call:** Assessment Submission Service publishes `assessorflow.email.request.participant-report` per participant → Email Service sends emails → Orchestrator publishes `assessorflow.workflow.complete`

The assessor clicks "Send Reports" after reviewing the detailed reports (2.7.3). This triggers email distribution to all participants (or a subset).

```
POST /api/v1/assessments/{assessment_id}/reports/distribute
```

**Headers:** `Authorization: Bearer {access_token}`

**Request:**
```json
{
  "participant_ids": ["uuid-1", "uuid-2"]
}
```

| Field | Type | Notes |
|-------|------|-------|
| participant_ids | array of UUIDs (optional) | If provided, distribute only to these participants. If empty or omitted, distribute to ALL evaluated participants |

**Response (202):**
```json
{
  "assessment_id": "uuid",
  "distributed_count": 4,
  "status": "distributing"
}
```

**Side effects:**
1. Publishes `assessorflow.email.request.participant-report` per participant → Email Service sends emails
2. Updates `participant_reports.status` → `sent` for distributed reports
3. Publishes `assessorflow.workflow.complete` → Orchestrator marks workflow as `completed`

**Errors:**
| Status | Reason |
|--------|--------|
| 400 | No reports to distribute |
| 404 | Assessment not found |
| 409 | Reports already distributed |

---

### 2.8 Workflow Status (for Frontend polling)

#### 2.8.1 Get Assessment Workflow Status

**Type:** External
**Caller:** Frontend → **Assessment Submission Service**
**Phase:** All phases (assessor polls to track progress after submitting materials)

```
GET /api/v1/assessments/{assessment_id}/status
```

**Headers:** `Authorization: Bearer {access_token}`

**Response (200):**
```json
{
  "assessment_id": "uuid",
  "workflow_id": "wf_9f3a21",
  "current_phase": "evaluation",
  "status": "evaluating",
  "progress": {
    "materials_validated": true,
    "classification_complete": true,
    "questions_generated": true,
    "questions_approved": true,
    "total_participants": 4,
    "submissions_completed": 4,
    "evaluations_completed": 2,
    "reports_completed": 0
  }
}
```

---

### 2.9 Cross-Service Flow Diagrams (Missing from T6 audit)

> These flows span multiple services and Pub/Sub events. They complete the picture for paths not covered by individual endpoint flow diagrams.

#### 2.9.1 Material Insufficiency → Web Research → Re-Classification (Phase 4 → 5 → 4/6)

```
Phase 4 (Sufficiency Check — material insufficient):
  Orchestrator ──Pub/Sub──→ Classification Agent         [assessorflow.classification.trigger (action: sufficiency_check)]
  Classification Agent ──gRPC──→ Assessment Submission    [2.1.2b GetAssessmentConfig]
  Classification Agent ──gRPC──→ Knowledge Service        [3.2.3 GetChunksByWorkflow (chunks already stored by Validator Agent)]
  Classification Agent determines: material NOT sufficient
  Classification Agent ──Pub/Sub──→ Orchestrator          [assessorflow.classification.insufficient]

  Orchestrator presents options to assessor (via frontend):
    Option A — Resubmit: assessor uploads new materials → restart from Phase 2
    Option B — Web search: assessor agrees → continue below

Phase 5 (Web Research + Validation):
  Orchestrator ──Pub/Sub──→ Web Research Agent            [assessorflow.web-research.trigger]
  Web Research Agent performs web search (text-only results)
  Web Research Agent ──Pub/Sub──→ Orchestrator            [assessorflow.web-research.complete (text in payload)]
  Orchestrator ──Pub/Sub──→ Validator Agent               [assessorflow.validation.trigger (web_research_validation)]
  Validator Agent checks content safety
  IF PROCEED:
    Validator Agent ──gRPC──→ Knowledge Service           [3.2.1 ProcessMaterial (web research text → enriched_chunks)]
    Validator Agent ──Pub/Sub──→ Orchestrator             [assessorflow.validation.complete]
  IF TERMINATE:
    Validator Agent ──Pub/Sub──→ Orchestrator             [assessorflow.validation.complete (TERMINATE)]
    Web research content rejected; proceed with original materials only

Phase 4/6 (Re-Classification with enriched materials):
  Orchestrator ──Pub/Sub──→ Classification Agent          [assessorflow.classification.trigger (action: topic_extraction)]
  Classification Agent ──gRPC──→ Knowledge Service        [3.2.3 GetChunksByWorkflow (now includes enriched_chunks if validated)]
  Classification Agent extracts topics from all content
  Classification Agent ──gRPC──→ Knowledge Service        [3.1.1 StoreTopics]
  Classification Agent ──Pub/Sub──→ Orchestrator          [assessorflow.classification.complete]
  → Proceeds to Phase 7 (Q&A Generation)
```

---

#### 2.9.2 Workflow Completion (Phase 12)

```
Phase 12 (Workflow Completion):
  Orchestrator confirms: reports_completed == total_participants
  Orchestrator ──updates──→ workflows.current_phase = 'completed'
  Orchestrator ──Pub/Sub──→ (broadcast)                   [assessorflow.workflow.complete]
  Orchestrator ──Pub/Sub──→ Email Service (per participant) [assessorflow.email.request.participant-report]
  Email Service ──email──→ Each participant                [report link with signed token]
  Participant ──Frontend──→ Assessment Submission           [2.7.2 Get Report]
  Assessor ──Frontend──→ Assessment Submission              [2.7.3 Get All Reports]
```

---

## 3. Knowledge Service

> Owns the chunking, embedding, and vector storage pipeline. Has 1 external REST endpoint (admin policy management) and 7 internal gRPC endpoints. Receives pre-extracted text from Validator Agent (Phase 3/5) — no longer downloads files from Cloud Storage. Used by Validator Agent (pass extracted text for chunking + embedding), Classification Agent (read chunks + store topics), Q&A Generation Agent (read topics + similarity search), and Evaluator Agent (chunk retrieval for grounding).

> **End-to-end flow for Knowledge Service:**
>
> ```
> System Setup / Ongoing Admin:
>   Admin ──REST──→ Knowledge Service                       [3.3.1 Add Policy]
>   Migration scripts ──gRPC──→ Knowledge Service           [3.3.1b StorePolicyChunks]
>
> Phase 3 (Material Validation — Validator Agent):
>   Validator Agent ──gRPC──→ Knowledge Service             [3.2.1 ProcessMaterial (extracted text → KS chunks, embeds, stores)]
>   Validator Agent ──gRPC──→ Knowledge Service             [3.2.1 ProcessMaterial (rubric text → policy_chunks, if rubric uploaded)]
>
> Phase 4/6 (Classification):
>   Classification Agent ──gRPC──→ Knowledge Service       [3.2.3 GetChunksByWorkflow (read chunks for sufficiency check + topic extraction)]
>   Classification Agent ──gRPC──→ Knowledge Service       [3.1.1 StoreTopics]
>
> Phase 5 (Web Research Validation — Validator Agent):
>   Validator Agent ──gRPC──→ Knowledge Service             [3.2.1 ProcessMaterial (web research text → enriched_chunks)]
>
> Phase 7 (Q&A Generation):
>   Q&A Gen Agent ──gRPC──→ Knowledge Service              [3.1.2 GetTopics]
>   Q&A Gen Agent ──gRPC──→ Knowledge Service              [3.2.2 SimilaritySearch]
>
> Phase 7a (Quality Validation):
>   Evaluator Agent ──gRPC──→ Knowledge Service            [3.2.4 GetChunksByIds]
>   Evaluator Agent ──gRPC──→ Knowledge Service            [3.3.2 SearchPolicies]
>
> Phase 10 (Participant Evaluation):
>   Evaluator Agent ──gRPC──→ Knowledge Service            [3.2.4 GetChunksByIds]
>   Evaluator Agent ──gRPC──→ Knowledge Service            [3.3.2 SearchPolicies]
>   Guardrails Service (L-10) ──gRPC──→ Knowledge Service   [3.3.2 SearchPolicies]
> ```

### 3.1 Topics

#### 3.1.1 Store Topics

**Type:** Internal (gRPC)
**Phase:** 6
**Caller:** Classification Agent → **Knowledge Service**
**Triggered by:** Classification Agent completes topic extraction after receiving `assessorflow.classification.trigger` (action: `topic_extraction`)
**After this call:** Classification Agent publishes `assessorflow.classification.complete` → Orchestrator triggers Q&A Gen Agent

```
StoreTopics(workflow_id, topics) → TopicResult
```

**Request:**
```json
{
  "workflow_id": "wf_9f3a21",
  "main_topic": "Object-Oriented Programming",
  "subtopics": [
    "Encapsulation",
    "Polymorphism",
    "Abstraction",
    "Inheritance",
    "Classes and Objects",
    "Methods",
    "Interfaces",
    "Access Modifiers"
  ]
}
```

**Response (201):**
```json
{
  "main_topic_id": "uuid",
  "subtopic_ids": [
    { "name": "Encapsulation", "id": "uuid" },
    { "name": "Polymorphism", "id": "uuid" }
  ],
  "total_subtopics": 8
}
```

---

#### 3.1.2 Get Topics

**Type:** Internal (gRPC)
**Phase:** 7
**Caller:** Q&A Generation Agent → **Knowledge Service**
**Triggered by:** Q&A Gen Agent receives `assessorflow.qa-generation.trigger` from Orchestrator, needs classified topics to plan question allocation

```
GetTopics(workflow_id) → Topics
```

**Response:**
```json
{
  "workflow_id": "wf_9f3a21",
  "main_topic": {
    "id": "uuid",
    "name": "Object-Oriented Programming"
  },
  "subtopics": [
    { "id": "uuid", "name": "Encapsulation" },
    { "id": "uuid", "name": "Polymorphism" }
  ]
}
```

---

### 3.2 Document Chunks

#### 3.2.1 Process and Store Material

**Type:** Internal (gRPC)
**Phase:** 3, 5
**Caller:** Validator Agent → **Knowledge Service**
**Triggered by:** Validator Agent extracts text (via OCR/direct parsing) and passes the pre-extracted text to Knowledge Service for chunking and embedding

Knowledge Service owns the **chunking and embedding pipeline** — it receives pre-extracted text from the Validator Agent, chunks it, embeds each chunk (via Model Broker gRPC call), and stores the results. The Validator Agent handles all file downloading, OCR, and content safety checking upstream — Knowledge Service never downloads files from Cloud Storage directly.

```
ProcessMaterial(material_text) → ProcessResult
```

**Request:**
```json
{
  "workflow_id": "wf_9f3a21",
  "assessment_id": "uuid",
  "material_id": "uuid",
  "content_text": "Encapsulation is one of the four fundamental OOP concepts. It bundles data with the methods that operate on it...",
  "source_file": "world_history.pdf",
  "source_type": "direct_text",
  "source": "upload"
}
```

| Field | Type | Notes |
|-------|------|-------|
| workflow_id | string | Originates from Orchestrator `workflows.id` |
| assessment_id | string (UUID) | References `assessment_configs.id`. **Required when `source = 'rubric'`** — used to set `policy_chunks.assessment_id` so rubric chunks are scoped to this assessment. Optional for other sources (can be derived from workflow_id) |
| material_id | string (UUID) | References `assessment_materials.id` (for `source = 'upload'`) or `assessment_rubrics.id` (for `source = 'rubric'`). NULL for `source = 'web_research'` (no file record) |
| content_text | string | Pre-extracted text content from the Validator Agent. For `upload`: extracted via OCR or direct text parsing. For `web_research`: collected text from web sources. For `rubric`: extracted rubric text |
| source_file | string | Original filename (e.g. `world_history.pdf`). For web research: source URL |
| source_type | string | `direct_text` (text parsed directly from PDF/DOCX) or `ocr_extracted` (text extracted via Vertex AI Vision OCR). Stored in `document_chunks.source_type` for downstream quality awareness |
| source | string | `upload` (assessor learning materials), `web_research` (Web Research Agent output), or `rubric` (assessor marking rubric) |

**Response (201):**
```json
{
  "material_id": "uuid",
  "chunks_created": 24,
  "workflow_id": "wf_9f3a21"
}
```

**What Knowledge Service does internally:**
1. Receives pre-extracted text from Validator Agent via `content_text`
2. Splits into chunks (chunk size and overlap strategy managed internally)
3. Embeds each chunk via Model Broker (gRPC call to L-09)
4. Routes based on `source` field:
   - `source = 'upload'` → stores in `document_chunks` table (Document KB) with `source_type` from request
   - `source = 'web_research'` → stores in `enriched_chunks` table (Enriched KB)
   - `source = 'rubric'` → stores in `policy_chunks` table (Policy KB) with `assessment_id` set and `source = 'assessor_rubric'`

This keeps provenance clear — learning materials, web research, and marking rubrics are stored in separate vector tables. Knowledge Service internally queries the relevant KBs and merges results during similarity search (RAG Router is an internal module within Knowledge Service, not a separate microservice).

---

#### 3.2.2 Similarity Search

**Type:** Internal (gRPC)
**Phase:** 7, 7a, 10
**Caller:** Q&A Generation Agent → **Knowledge Service** (Phase 7 — retrieve chunks for question generation), Evaluator Agent → **Knowledge Service** (Phase 7a/10 — retrieve chunks for grounding validation)

Pure semantic similarity search — the query text (a subtopic name selected by the Q&A Generation Agent) is embedded and compared against stored chunk embeddings using cosine similarity. No pre-filtering by topic labels. This is how the RAG pipeline demonstrates real embedding-based retrieval.

The Q&A Generation Agent does NOT generate its own queries — it receives the full list of subtopics from Knowledge Service (3.1.2 GetTopics), selects a subset based on assessment config (question count, difficulty, purpose), and uses those **subtopic names directly** as the query text.

```
SimilaritySearch(workflow_id, queries[]) → Chunks[]
```

**Request:**
```json
{
  "workflow_id": "wf_9f3a21",
  "queries": [
    { "query_text": "Ancient Civilizations", "top_k": 3 },
    { "query_text": "Industrial Revolution", "top_k": 1 }
  ]
}
```

| Field | Type | Notes |
|-------|------|-------|
| workflow_id | string | Scopes the search to chunks belonging to this workflow's assessment materials |
| queries | array | One entry per selected subtopic. The Q&A Gen Agent selects which subtopics to use from the full list provided by Classification Agent |
| query_text | string | The subtopic name as provided by Classification Agent (e.g. "Polymorphism", "Ancient Civilizations"). Embedded by Knowledge Service (via Model Broker) and compared against chunk embeddings |
| top_k | integer | Number of most similar chunks to return per query. Dynamically adjusted by Q&A Gen Agent based on purpose, question count, and difficulty level |

**Response:**
```json
{
  "results": [
    {
      "chunk_id": "uuid",
      "query_text": "Explain polymorphism in Object-Oriented Programming",
      "content": "Polymorphism allows objects of different classes to respond to the same method call...",
      "score": 0.92,
      "metadata": {
        "source_file": "world_history.pdf",
        "source_page": 2
      }
    }
  ]
}
```

| Field | Type | Notes |
|-------|------|-------|
| chunk_id | string (UUID) | References `document_chunks.id` |
| query_text | string | Which query this result matched against |
| content | string | The chunk text content |
| score | float | Cosine similarity score (0.0–1.0). Higher = more relevant |
| metadata | object | Source file and page for traceability |

---

#### 3.2.3 Get Chunks by Workflow

**Type:** Internal (gRPC)
**Phase:** 4, 6
**Caller:** Classification Agent → **Knowledge Service**
**Triggered by:** Chunks are already stored by the Validator Agent (Phase 3) via `ProcessMaterial` (3.2.1). The Classification Agent reads them for sufficiency check (Phase 4) and topic extraction (Phase 6)

Retrieves all document chunks for a given workflow. The Classification Agent uses these chunks (not the original files) for topic extraction — the chunks are already cleaned, parsed, and sized for LLM processing. The Classification Agent does NOT call `ProcessMaterial` — that is the Validator Agent's responsibility in Phase 3.

```
GetChunksByWorkflow(workflow_id) → Chunks[]
```

**Request:**
```json
{
  "workflow_id": "wf_9f3a21"
}
```

**Response:**
```json
{
  "workflow_id": "wf_9f3a21",
  "total_chunks": 24,
  "chunks": [
    {
      "id": "uuid-1",
      "content": "Encapsulation is one of the four fundamental OOP concepts...",
      "source_file": "world_history.pdf",
      "source_page": 2,
      "chunk_index": 0
    },
    {
      "id": "uuid-2",
      "content": "Polymorphism allows objects of different classes to respond to the same method call...",
      "source_file": "world_history.pdf",
      "source_page": 3,
      "chunk_index": 1
    }
  ]
}
```

---

#### 3.2.4 Get Chunks by IDs

**Type:** Internal (gRPC)
**Phase:** 7a, 10
**Caller:** Evaluator Agent → **Knowledge Service**
**Purpose:** Retrieves specific document chunks by their IDs (from `source_chunk_ids` in question metadata). Used to verify that generated Q&A is grounded in source material (Phase 7a) and to evaluate non-structured answers against source material (Phase 10)

```
GetChunksByIds(chunk_ids[]) → Chunks[]
```

**Request:**
```json
{
  "chunk_ids": ["uuid-1", "uuid-2", "uuid-3"]
}
```

**Response:**
```json
{
  "chunks": [
    {
      "id": "uuid-1",
      "content": "Encapsulation is one of the four fundamental OOP concepts...",
      "source_file": "world_history.pdf",
      "source_page": 2
    }
  ]
}
```

---

#### 3.2.5 Get Chunk by ID (External — REST)

**Type:** External (REST)
**Phase:** 8, 12
**Caller:** Frontend → **Knowledge Service**
**Purpose:** Frontend retrieves source chunk text for display in HITL review (qa_review.html — "Trace to Source" panel) and evaluation report inspector (eval_report_inspector.html — grounding citations). The frontend gets `chunk_id` from question `grounding_citations[]` and calls this endpoint to fetch the full chunk text.

```
GET /api/v1/knowledge/chunks/{chunk_id}
```

**Headers:** `Authorization: Bearer {access_token}`

**Response (200):**
```json
{
  "id": "uuid-1",
  "content": "Encapsulation is one of the four fundamental OOP concepts. It bundles data with the methods that operate on it and restricts direct access to some components...",
  "source_file": "world_history.pdf",
  "source_page": 2,
  "source_type": "direct_text",
  "chunk_index": 0
}
```

| Field | Type | Notes |
|-------|------|-------|
| id | string (UUID) | Chunk ID |
| content | string | Full chunk text — displayed in the grounding panel |
| source_file | string | Original filename the chunk came from |
| source_page | integer or null | Page number in the source file |
| source_type | string | `direct_text` or `ocr_extracted` — indicates how the text was obtained |
| chunk_index | integer | Order within the source file |

**Errors:**
| Status | Reason |
|--------|--------|
| 404 | Chunk not found |

---

### 3.3 Policy Chunks

#### 3.3.1 Add Policy / Marking Rubric (External — REST, Admin only)

**Type:** External (REST)
**Phase:** System setup / ongoing admin
**Caller:** Frontend (Admin) → **Knowledge Service**
**Auth:** `Authorization: Bearer {access_token}` — requires `role = admin`

Admin adds marking rubrics, grading guidance, assessment rules, and content safety policies. These are **system-wide defaults** — they apply to all assessments, not tied to any specific workflow. The Evaluator Agent and Guardrails Service search them by context at runtime.

```
POST /api/v1/admin/policies
```

**Headers:** `Authorization: Bearer {access_token}`

**Request:**
```json
{
  "policies": [
    {
      "policy_type": "rubric",
      "content": "For comprehension questions, accept paraphrased answers if core meaning is preserved.",
      "metadata": {
        "applies_to_question_type": "non_structured",
        "applies_to_difficulty": "easy",
        "purpose": "topic_revision"
      }
    },
    {
      "policy_type": "content_safety",
      "content": "Generated questions must not contain culturally biased references or assume prior knowledge beyond uploaded materials.",
      "metadata": {
        "applies_to_question_type": "all",
        "applies_to_difficulty": "all"
      }
    }
  ]
}
```

| Field | Type | Notes |
|-------|------|-------|
| policy_type | string | `rubric`, `grading_guidance`, `assessment_rule`, `content_safety` |
| content | string | The policy text. Embedded by Knowledge Service (via Model Broker) for semantic search |
| metadata | object | Context for when this policy applies — `applies_to_question_type` (`structured`, `non_structured`, `all`), `applies_to_difficulty` (`easy`, `medium`, `hard`, `all`), `purpose` (optional) |

**Response (201):**
```json
{
  "policies_stored": 2
}
```

**Errors:**
| Status | Reason |
|--------|--------|
| 401 | Unauthorized |
| 403 | User role is not `admin` |

**Note:** No `workflow_id` — policies are global. Embeddings are generated by Knowledge Service internally (via Model Broker). Admin can add policies at any time — during initial setup or ongoing.

---

#### 3.3.1b Store Policy Chunks (Internal — gRPC)

**Type:** Internal (gRPC)
**Caller:** Migration scripts / deployment seeding → **Knowledge Service**
**Purpose:** Batch seeding of policies during deployment. Same data as 3.3.1 but for automated scripts (mTLS auth, no JWT).

```
StorePolicyChunks(policies[]) → StoreResult
```

**Request/Response:** Same as 3.3.1.

---

#### 3.3.2 Search Policies

**Type:** Internal (gRPC)
**Phase:** 7a, 10
**Caller:** Evaluator Agent → **Knowledge Service** (retrieve grading guidance and rubrics), Guardrails Service (L-10) → **Knowledge Service** (retrieve content safety and fairness policies)

```
SearchPolicies(query, policy_type?, assessment_id?) → PolicyChunks[]
```

**Request:**
```json
{
  "query": "grading open-ended English grammar verb tense easy difficulty",
  "policy_type": "grading_guidance",
  "assessment_id": "uuid",
  "top_k": 3
}
```

| Field | Type | Notes |
|-------|------|-------|
| query | string | Semantic search query — embedded and compared against policy chunk embeddings |
| policy_type | string (optional) | Filter by type: `rubric`, `grading_guidance`, `assessment_rule`, `content_safety`. Omit to search all types |
| assessment_id | string (UUID, optional) | When provided: returns system defaults (`assessment_id IS NULL`) **plus** rubrics matching this assessment. When omitted: returns system defaults only (backwards compatible) |
| top_k | integer | Number of most relevant policy chunks to return |

**Response:**
```json
{
  "results": [
    {
      "id": "uuid",
      "policy_type": "grading_guidance",
      "content": "For comprehension questions, accept paraphrased answers if core meaning is preserved.",
      "score": 0.89,
      "source": "system_default"
    }
  ]
}
```

---

## 4. Decision Audit Service (L-11)

> All agents write here via gRPC fire-and-forget. Append-only — no updates, no deletes.
> **Data ownership:** Dale owns both the Decision Audit Service and the data tables (`agent_decision_log`, `token_usage_ledger`, `evaluation_audit_log`). The read endpoints below (4.4–4.6) are **Dale's responsibility to implement**. They are documented here for completeness so the frontend team knows what to call.
>
> **Callers and when they write:**
>
> | Agent | When | Decision Type |
> |-------|------|---------------|
> | Classification Agent → **L-11** | Phase 4 (sufficiency check), Phase 6 (topic extraction) | `material_sufficiency`, `topic_extraction` |
> | Q&A Generation Agent → **L-11** | Phase 7 (question generation) | `question_generation` |
> | Evaluator Agent → **L-11** | Phase 7a (quality validation), Phase 10 (grading) | `question_quality_validation`, `grading` |
> | Web Research Agent → **L-11** | Phase 5 (web search) | `web_research` |
> | Reporting Agent → **L-11** | Phase 11 (report generation) | `report_generation` |

### 4.1 Log Agent Decision

**Type:** Internal (gRPC, fire-and-forget)
**Phase:** All agent phases
**Caller:** All agents → **Decision Audit Service (L-11)**
**Note:** Fire-and-forget — agents do not wait for confirmation. Non-blocking so it does not slow down the workflow.

```
LogDecision(decision) → Ack
```

**Request:**
```json
{
  "workflow_id": "wf_9f3a21",
  "agent_name": "evaluator-agent",
  "decision_type": "grading",
  "input_summary": { "participant_id": "uuid", "question_id": "uuid" },
  "output_summary": { "score": 15.0, "max_score": 20.0 },
  "reasoning_steps": [
    { "step": 1, "action": "Retrieved model answer and source chunks" },
    { "step": 2, "action": "Compared participant answer against model answer" },
    { "step": 3, "action": "Identified missing interface exposure example" }
  ],
  "confidence_score": 0.85,
  "prompt_version": "evaluator/grade_open_ended@v2",
  "model_id": "claude-sonnet-4-6",
  "grounding_sources": ["chunk_101", "chunk_302"]
}
```

**Response:** Acknowledgement only (fire-and-forget — caller does not wait for confirmation).

---

### 4.2 Log Token Usage

**Type:** Internal (gRPC, fire-and-forget)
**Caller:** All agents → **Decision Audit Service (L-11)** (after every LLM call via Model Broker)
**Note:** Logged after every LLM invocation. Used for cost monitoring, Model Broker budget enforcement, and experiment cost tracking via Langfuse

```
LogTokenUsage(usage) → Ack
```

**Request:**
```json
{
  "workflow_id": "wf_9f3a21",
  "agent_name": "qa-generation-agent",
  "model_id": "claude-sonnet-4-6",
  "prompt_tokens": 1250,
  "completion_tokens": 800,
  "total_tokens": 2050,
  "estimated_cost_usd": 0.012300,
  "prompt_version": "qa-gen/generate_questions@v3"
}
```

---

### 4.3 Log Evaluation Audit

**Type:** Internal (gRPC, fire-and-forget)
**Phase:** 7a, 10
**Caller:** Evaluator Agent → **Decision Audit Service (L-11)**
**Note:** Separate from `evaluation_details` (which stores scores in Assessment Submission Service). This captures the decision *process* — reasoning, grounding sources, model used — for grading transparency and Responsible AI evidence

```
LogEvaluationAudit(audit) → Ack
```

**Request:**
```json
{
  "workflow_id": "wf_9f3a21",
  "evaluation_id": "uuid",
  "participant_id": "uuid",
  "question_id": "uuid",
  "evaluation_phase": "participant_grading",
  "decision": "score_assigned",
  "reasoning": {
    "approach": "LLM-based evaluation against model answer and source chunks",
    "key_factors": ["2 of 3 differences identified", "missing interface example"]
  },
  "grounding_sources": ["chunk_101", "chunk_302"],
  "model_id": "claude-sonnet-4-6",
  "prompt_version": "evaluator/grade_open_ended@v2"
}
```

---

### 4.4 Get Workflow Events (External — REST, Dale to implement)

**Type:** External (REST)
**Caller:** Frontend → **Orchestrator Agent** (reads from `workflow_events` table owned by Orchestrator)
**Owner:** Dale — Orchestrator owns `workflow_events` data and exposes this read endpoint
**Phase:** All phases (Agent Trace & Audit screen)

```
GET /api/v1/workflows/{workflow_id}/events
```

**Headers:** `Authorization: Bearer {access_token}`

**Response (200):**
```json
{
  "events": [
    {
      "event_id": "uuid",
      "workflow_id": "wf_9f3a21",
      "event_type": "assessorflow.workflow.start",
      "source_agent": "assessment-submission-service",
      "summary": "Workflow started. Material validation in progress.",
      "timestamp": "2026-03-21T10:00:00Z",
      "payload": { }
    },
    {
      "event_id": "uuid",
      "workflow_id": "wf_9f3a21",
      "event_type": "assessorflow.classification.complete",
      "source_agent": "classification-agent",
      "summary": "Topic extraction and subtopic identification complete.",
      "timestamp": "2026-03-21T10:02:14Z",
      "payload": { }
    },
    {
      "event_id": "uuid",
      "workflow_id": "wf_9f3a21",
      "event_type": "assessorflow.qa-generation.complete",
      "source_agent": "qa-generation-agent",
      "summary": "Generated questions based on extracted subtopics. Iteration 1.",
      "timestamp": "2026-03-21T10:03:30Z",
      "payload": { }
    },
    {
      "event_id": "uuid",
      "workflow_id": "wf_9f3a21",
      "event_type": "assessorflow.quality-validation.failed",
      "source_agent": "evaluator-agent",
      "summary": "Failed quality validation. Weak MCQ distractors detected. Bouncing back to QA Gen.",
      "timestamp": "2026-03-21T10:04:15Z",
      "payload": { }
    }
  ]
}
```

| Field | Type | Notes |
|-------|------|-------|
| event_id | string (UUID) | Unique event identifier |
| workflow_id | string | References `workflows.id` |
| event_type | string | Pub/Sub topic name in dot-notation (e.g. `assessorflow.classification.complete`). Rendered as a badge in the timeline UI |
| source_agent | string | Service/agent that published the event (e.g. `classification-agent`, `evaluator-agent`) |
| summary | string | Human-readable description of what happened. Generated by the Orchestrator when recording the event in `workflow_events`. Displayed as the timeline entry body |
| timestamp | string (ISO 8601) | When the event was published |
| payload | object | Raw Pub/Sub payload for detailed inspection. See `pubsub.md` Section 5 for schemas per event type |

> Events are returned in **chronological order** (oldest first). The frontend renders these as a vertical timeline — each event becomes a card showing `source_agent` as the title, `summary` as the description, `event_type` as a badge, and `timestamp` on the right.

---

### 4.5 Get Decision Logs (External — REST, Dale to implement)

**Type:** External (REST)
**Caller:** Frontend → **Decision Audit Service (L-11)**
**Owner:** Dale — Decision Audit Service owns `agent_decision_log` data and exposes this read endpoint
**Phase:** All phases (Agent Trace & Audit screen — decision timeline + reasoning viewer)

```
GET /api/v1/workflows/{workflow_id}/decisions
```

**Headers:** `Authorization: Bearer {access_token}`

**Response (200):**
```json
{
  "decisions": [
    {
      "decision_id": "uuid",
      "workflow_id": "wf_9f3a21",
      "agent_name": "evaluator-agent",
      "decision_type": "grading",
      "model_id": "claude-sonnet-4-6",
      "confidence_score": 0.85,
      "reasoning_steps": [
        { "step": 1, "action": "Retrieved model answer and source chunks" },
        { "step": 2, "action": "Compared participant answer against model answer" },
        { "step": 3, "action": "Identified missing interface exposure example" }
      ],
      "grounding_sources": ["chunk_101", "chunk_302"],
      "prompt_version": "evaluator/grade_open_ended@v2",
      "timestamp": "2026-03-21T15:00:00Z"
    }
  ]
}
```

> Read-only — compliant with append-only invariant (Invariant #5). This endpoint reads from `agent_decision_log`, it does not modify it.

---

### 4.6 Get Token Usage (External — REST, Dale to implement)

**Type:** External (REST)
**Caller:** Frontend → **Decision Audit Service (L-11)**
**Owner:** Dale — Decision Audit Service owns `token_usage_ledger` data and exposes this read endpoint
**Phase:** All phases (Agent Trace & Audit screen — token usage card)

```
GET /api/v1/workflows/{workflow_id}/token-usage
```

**Headers:** `Authorization: Bearer {access_token}`

**Response (200):**
```json
{
  "workflow_id": "wf_9f3a21",
  "total_prompt_tokens": 5200,
  "total_completion_tokens": 3800,
  "total_tokens": 9000,
  "total_estimated_cost_usd": 0.054,
  "breakdown": [
    {
      "agent_name": "qa-generation-agent",
      "model_id": "claude-sonnet-4-6",
      "prompt_tokens": 2500,
      "completion_tokens": 1600,
      "total_tokens": 4100,
      "estimated_cost_usd": 0.025,
      "call_count": 3
    },
    {
      "agent_name": "evaluator-agent",
      "model_id": "claude-sonnet-4-6",
      "prompt_tokens": 2700,
      "completion_tokens": 2200,
      "total_tokens": 4900,
      "estimated_cost_usd": 0.029,
      "call_count": 8
    }
  ]
}
```

> Read-only — aggregates from `token_usage_ledger` grouped by agent. `call_count` is the number of LLM invocations per agent for this workflow.

---

## 5. Orchestrator Agent

> The Orchestrator's primary communication is via Pub/Sub (see `pubsub.md`). These REST endpoints are for the Frontend to check workflow status. The Orchestrator does not expose gRPC to other agents — it communicates exclusively via Pub/Sub (hub-and-spoke pattern).
>
> **Association:**
> ```
> Frontend ──REST──→ Orchestrator    [5.1 List Workflows, 5.2 Get Workflow Status]
> All other communication is via Pub/Sub — see pubsub.md for full topic inventory
> ```

### 5.1 List Workflows

**Type:** External
**Caller:** Frontend → **Orchestrator Agent**
**Phase:** All phases (assessor dashboard — lists all workflows)

```
GET /api/v1/workflows?status={status}&page={page}&page_size={page_size}
```

**Headers:** `Authorization: Bearer {access_token}`

**Query parameters:**

| Parameter | Type | Default | Notes |
|-----------|------|---------|-------|
| status | string (optional) | all | Filter: `active`, `completed`, `awaiting_hitl`, `terminated` |
| page | integer | 1 | Pagination |
| page_size | integer | 20 | Pagination |

**Response (200):**
```json
{
  "workflows": [
    {
      "workflow_id": "wf_9f3a21",
      "assessment_id": "uuid",
      "assessment_title": "OOP Mid-Term Quiz",
      "current_phase": "evaluation",
      "current_agent": "evaluator-agent",
      "status": "active",
      "last_reason_code": null,
      "last_reason_message": null,
      "total_participants": 4,
      "submissions_completed": 4,
      "evaluations_completed": 2,
      "reports_completed": 0,
      "created_at": "2026-03-21T10:30:00Z",
      "updated_at": "2026-03-21T15:00:00Z"
    },
    {
      "workflow_id": "wf_b7c432",
      "assessment_id": "uuid",
      "assessment_title": "History Essay Assessment",
      "current_phase": "terminated",
      "current_agent": null,
      "status": "terminated",
      "last_reason_code": "HARMFUL_CONTENT",
      "last_reason_message": "Document contains content that violates content safety policy.",
      "total_participants": 3,
      "submissions_completed": 0,
      "evaluations_completed": 0,
      "reports_completed": 0,
      "created_at": "2026-03-22T09:00:00Z",
      "updated_at": "2026-03-22T09:05:00Z"
    }
  ],
  "total": 12,
  "page": 1,
  "page_size": 20
}
```

| Field | Type | Notes |
|-------|------|-------|
| assessment_title | string | From `assessment_configs.assessment_title` |
| current_phase | string | Phase label (e.g. `evaluation`, `human_review`, `terminated`) — same labels as `workflows.current_phase` |
| current_agent | string or null | Agent currently processing (e.g. `evaluator-agent`). NULL when waiting on human or participants, or when terminated |
| status | string | `active` (workflow in progress), `completed` (all reports done), `awaiting_hitl` (waiting for assessor review in Phase 8), `terminated` (system-terminated, e.g. MRC/content safety failure) |
| last_reason_code | string or null | Updated on every phase transition. The `reason_code` from the latest agent event (e.g. `VALIDATION_PASSED`, `INSUFFICIENT_MATERIAL`, `QUALITY_FAILED`, `HARMFUL_CONTENT`). Shown in dashboard |
| last_reason_message | string or null | Updated on every phase transition. Human-readable explanation from the latest event. Shown in dashboard |

---

### 5.2 Workflow Aggregate Stats

**Type:** External
**Caller:** Frontend → **Orchestrator Agent**
**Purpose:** Dashboard summary cards — counts of active workflows, HITL-pending, and recently completed reports. Avoids fetching all workflows just to compute counts.

```
GET /api/v1/workflows/stats
```

**Headers:** `Authorization: Bearer {access_token}`

**Response (200):**
```json
{
  "active_workflows": 5,
  "awaiting_hitl_review": 2,
  "completed_reports_24h": 12
}
```

| Field | Type | Notes |
|-------|------|-------|
| active_workflows | integer | Count of workflows with `status = 'active'` |
| awaiting_hitl_review | integer | Count of workflows with `current_phase = 'human_review'` |
| completed_reports_24h | integer | Count of participant reports with `status = 'completed'` and `generated_at` within the last 24 hours |

---

### 5.3 Get Workflow Status

**Type:** External
**Caller:** Frontend → **Orchestrator Agent**
**Phase:** All phases (assessor polls to track single workflow progress)

```
GET /api/v1/workflows/{workflow_id}
```

**Headers:** `Authorization: Bearer {access_token}`

**Response (200):**
```json
{
  "workflow_id": "wf_9f3a21",
  "assessment_id": "uuid",
  "assessment_title": "OOP Mid-Term Quiz",
  "current_phase": "evaluation",
  "current_agent": "evaluator-agent",
  "question_set_id": "uuid",
  "total_participants": 4,
  "submissions_completed": 4,
  "evaluations_completed": 2,
  "reports_completed": 0,
  "created_at": "2026-03-21T10:30:00Z",
  "updated_at": "2026-03-21T15:00:00Z"
}
```

---

## 6. Email Service

> Email Service (#10) has **no REST or gRPC endpoints**. It is entirely Pub/Sub-driven:
>
> - **Subscribes to** 3 request topics: `assessorflow.email.request.assessor-review`, `assessorflow.email.request.assessment-link`, `assessorflow.email.request.participant-report`
> - **Publishes to** 3 deliver topics: `assessorflow.email.deliver.assessor-review`, `assessorflow.email.deliver.assessment-link`, `assessorflow.email.deliver.participant-report`
> - Uses a **two-stage pattern**: `request` (prepare email content) → `deliver` (send via SMTP)
> - Writes to `email_log` table in its own database to track send status
>
> See `pubsub.md` Sections 3 and 5.19–5.22 for full topic definitions and payload schemas.
>
> **This service is intentionally excluded from the API contract** — it has no callable endpoints. All communication is via Pub/Sub events published by the Orchestrator.

---

## Error Response Envelope

All error responses follow this structure:

```json
{
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "non_structured_question_count > 0 requires groups to be defined",
    "details": [
      {
        "field": "groups",
        "reason": "required when non_structured_question_count > 0"
      }
    ]
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| error.code | string | Machine-readable error code (e.g. `VALIDATION_FAILED`, `NOT_FOUND`, `UNAUTHORIZED`, `CONFLICT`) |
| error.message | string | Human-readable error description |
| error.details | array | Optional — field-level validation errors |

---

## API Summary by Service

| Service | External Endpoints (REST) | Internal Endpoints (gRPC) | Total |
|---------|--------------------------|--------------------------|-------|
| Identity and Access | 4 (login, refresh, logout, me) | 1 (validate token) | 5 |
| Assessment Submission | 22 (CRUD, rubrics, materials, start workflow, review, topics, invite, participate, reports, distribute, status) | 13 (get config, get materials, upload web research materials, question sets CRUD, get questions with answers, group member submissions, evaluations, reports) | 35 |
| Knowledge Service | 2 (admin: add policies, get chunk by ID) | 8 (topics, chunks, policies) | 10 |
| Decision Audit (L-11) | 2 (decisions, token-usage — Dale to implement) | 3 (decision, token usage, eval audit) | 5 |
| Orchestrator (events read) | 1 (workflow events — Dale to implement) | 0 | 1 |
| Orchestrator | 3 (list workflows, stats, workflow status) | 0 | 3 |
| **Total** | **34** | **25** | **59** |

> REST and gRPC endpoints are documented separately even when they serve the same data (e.g. 2.1.2 vs 2.1.2b). REST endpoints are for the frontend (JWT auth). gRPC endpoints are for agents (mTLS). This prevents confusion — REST uses HTTP paths + JSON, gRPC uses protobuf service definitions.
