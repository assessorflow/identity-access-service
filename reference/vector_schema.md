# AssessorFlow — Vector Schema Design (pgvector)

> Vector tables for the 3 Knowledge Bases. All tables use pgvector 0.8+ on PostgreSQL 18+.
> Owned by Knowledge Service (#3). Relational tables for Knowledge Service are in `schema.md` Section 3.

---

## 1. Document Knowledge Base

Stores chunked content from assessor-uploaded learning materials. Written by Knowledge Service when **Validator Agent** passes extracted text via `ProcessMaterial` (3.2.1) during Phase 3. The Validator Agent handles OCR and content safety — Knowledge Service receives pre-extracted text, chunks it, embeds it, and stores it here. Read by Classification Agent (via `GetChunksByWorkflow` 3.2.3 — for sufficiency check and topic extraction), Q&A Generation Agent (via `SimilaritySearch` 3.2.2 — for question creation), and Evaluator Agent (via `GetChunksByIds` 3.2.4 — for grounded scoring).

### `document_chunks`

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | = chunk_id (e.g. `chunk_101`) |
| workflow_id | VARCHAR(50) | NOT NULL | Originates from Orchestrator `workflows.id` |
| content | TEXT | NOT NULL | Chunk text |
| embedding | VECTOR(1536) | NOT NULL | pgvector — for similarity search |
| source_file | VARCHAR(255) | NOT NULL | e.g. `world_history.pdf` |
| source_page | INTEGER | | Page number if applicable |
| source_type | VARCHAR(50) | NOT NULL, DEFAULT 'direct_text' | `direct_text` (text extracted directly from PDF/DOCX) or `ocr_extracted` (text extracted via Vertex AI Vision OCR from scanned/image-heavy documents). Useful for downstream quality awareness — OCR-derived chunks may have lower fidelity |
| chunk_index | INTEGER | NOT NULL | Order within the source file |
| token_count | INTEGER | | Approximate token count of chunk content |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

**Indexes:**

```sql
CREATE INDEX idx_document_chunks_embedding
  ON document_chunks USING ivfflat (embedding vector_cosine_ops)
  WITH (lists = 100);

CREATE INDEX idx_document_chunks_workflow
  ON document_chunks (workflow_id);
```

**Usage by phase:**

| Phase | Agent | Operation |
|-------|-------|-----------|
| Phase 3 | Validator Agent → Knowledge Service | Validator Agent extracts text (OCR/direct) and passes to Knowledge Service via `ProcessMaterial` (3.2.1); Knowledge Service chunks, embeds, and INSERTs. Sets `source_type` based on extraction method |
| Phase 4/6 | Classification Agent → Knowledge Service | Classification Agent reads chunks via `GetChunksByWorkflow` (3.2.3) for sufficiency check and topic extraction. Chunks already stored by Validator Agent |
| Phase 7 | Q&A Generation Agent → Knowledge Service | SELECT via `SimilaritySearch` (3.2.2) — top-k per subtopic |
| Phase 7a | Evaluator Agent → Knowledge Service | SELECT via `GetChunksByIds` (3.2.4) for grounding validation of generated Q&A |
| Phase 10 | Evaluator Agent → Knowledge Service | SELECT via `GetChunksByIds` (3.2.4) for grounding evaluation of participant answers (non-structured only) |

---

## 2. Policy Knowledge Base

Stores assessment rules, rubrics, and grading guidance as vector-embedded chunks. Used by the Evaluator Agent for grounding grading decisions and by the Guardrails Service (L-10) for content safety and question quality validation.

### `policy_chunks`

> Stores two types of policies:
> - **System-wide defaults** (`assessment_id = NULL`, `source = 'system_default'`) — pre-seeded by Admin via `POST /api/v1/admin/policies`. Apply globally to all assessments.
> - **Assessor-uploaded rubrics** (`assessment_id = <uuid>`, `source = 'assessor_rubric'`) — uploaded per-assessment by the assessor in Phase 2. Processed by Knowledge Service via `ProcessMaterial` with `source = 'rubric'`. Provides explainability — the Evaluator Agent grounds its grading decisions in the assessor's own rubric.
>
> The Evaluator Agent and Guardrails Service search both types at runtime. Per-assessment rubrics are scoped to their assessment; system defaults apply to all.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| assessment_id | UUID | DEFAULT NULL | NULL = system-wide default. Non-null = assessor-uploaded rubric for a specific assessment |
| policy_type | VARCHAR(50) | NOT NULL | `rubric`, `grading_guidance`, `assessment_rule`, `content_safety` |
| content | TEXT | NOT NULL | Policy text |
| embedding | VECTOR(1536) | NOT NULL | pgvector — for similarity search |
| source | VARCHAR(255) | NOT NULL, DEFAULT 'system_default' | `system_default` (Admin-seeded) or `assessor_rubric` (assessor-uploaded) |
| metadata | JSONB | | Additional policy context (see sample below) |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

**Sample `metadata`:**
```json
{
  "applies_to_question_type": "non_structured",
  "applies_to_difficulty": "easy",
  "purpose": "topic_revision",
  "description": "Partial marks guidance for open-ended comprehension questions"
}
```

**Indexes:**

```sql
CREATE INDEX idx_policy_chunks_embedding
  ON policy_chunks USING ivfflat (embedding vector_cosine_ops)
  WITH (lists = 50);

CREATE INDEX idx_policy_chunks_type
  ON policy_chunks (policy_type);

CREATE INDEX idx_policy_chunks_assessment
  ON policy_chunks (assessment_id);
```

**Usage by phase:**

| Phase | Agent / Service | Operation |
|-------|-----------------|-----------|
| System setup / ongoing | Admin (via `POST /api/v1/admin/policies` or gRPC migration scripts) | INSERT system-wide default policies (`assessment_id = NULL`, `source = 'system_default'`) |
| Phase 2 | Assessment Submission Service → Knowledge Service | INSERT assessor-uploaded rubrics via `ProcessMaterial` (`assessment_id = <uuid>`, `source = 'assessor_rubric'`) |
| Phase 7a | Evaluator Agent | SELECT for quality validation — are questions aligned with assessment policy + assessor rubric? |
| Phase 7a | Guardrails Service (L-10) | SELECT for content safety — are generated questions free of bias, culturally appropriate? |
| Phase 10 | Evaluator Agent | SELECT for grounding grading decisions — searches both system defaults AND assessor rubric to determine how to award marks. Rubric-grounded reasoning is captured in `evaluation_audit_log` for explainability |
| Phase 10 | Guardrails Service (L-10) | SELECT for fairness validation — are grading decisions consistent and unbiased? |

**Example policies:**
- `rubric`: "Award partial marks for grammatically correct but incomplete answers"
- `grading_guidance`: "For comprehension questions, accept paraphrased answers if core meaning is preserved"
- `assessment_rule`: "Group answers should be evaluated holistically, not per-member contribution"
- `content_safety`: "Generated questions must not contain culturally biased references or assume prior knowledge beyond uploaded materials"

---

## 3. Enriched Knowledge Base

Stores web research results as vector-embedded chunks. Populated when source materials are insufficient (Phase 5). The Web Research Agent simulates assessor uploads — converts collected text to `.md` files and downloads images, stores them in Cloud Object Storage, and registers them in `assessment_materials` (source = `web_research`). The **Validator Agent** then validates these files through the same path as assessor uploads. If `PROCEED`, passes extracted text to Knowledge Service for chunking and embedding here. Kept separate from Document KB to maintain provenance.

### `enriched_chunks`

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| workflow_id | VARCHAR(50) | NOT NULL | Originates from Orchestrator `workflows.id` |
| topic_id | UUID | FK → topics.id | Associated topic |
| content | TEXT | NOT NULL | Web research text |
| embedding | VECTOR(1536) | NOT NULL | pgvector — for similarity search |
| source_url | VARCHAR(1024) | NOT NULL | URL where content was retrieved |
| source_type | VARCHAR(50) | NOT NULL, DEFAULT 'web_text' | `web_text` (text collected from web pages). Future: `web_ocr` if web images are OCR'd |
| retrieved_at | TIMESTAMPTZ | NOT NULL | When the web search was performed |
| metadata | JSONB | | Search context (see sample below) |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

**Sample `metadata`:**
```json
{
  "search_query": "Object-Oriented Programming encapsulation real-world examples",
  "relevance_score": 0.87,
  "domain": "geeksforgeeks.org",
  "snippet_title": "Encapsulation in Java with Examples"
}
```

**Indexes:**

```sql
CREATE INDEX idx_enriched_chunks_embedding
  ON enriched_chunks USING ivfflat (embedding vector_cosine_ops)
  WITH (lists = 50);

CREATE INDEX idx_enriched_chunks_workflow
  ON enriched_chunks (workflow_id);

CREATE INDEX idx_enriched_chunks_topic
  ON enriched_chunks (topic_id);
```

**Usage by phase:**

| Phase | Agent | Operation |
|-------|-------|-----------|
| Phase 5 | Validator Agent → Knowledge Service | Validator Agent validates web research text for content safety, then passes text to Knowledge Service via `ProcessMaterial` (3.2.1); Knowledge Service chunks, embeds, and INSERTs into `enriched_chunks` |
| Phase 7 | Q&A Generation Agent | SELECT via similarity search (supplementary to Document KB) |

---

## RAG Query Flow

When an agent performs a similarity search, the query text is embedded and compared against stored chunk embeddings using cosine similarity — **pure semantic search**, no pre-filtering by topic labels. This demonstrates a real RAG pipeline where relevance is determined by embedding distance, not exact category matching.

> **RAG Router is an internal module within Knowledge Service** — not a separate microservice. Agents call Knowledge Service endpoints directly (e.g. 3.2.2 SimilaritySearch). The routing, caching, and KB selection logic happens inside Knowledge Service.

```
Agent ──gRPC──→ Knowledge Service
                  └── RAG Router (internal module)
                        → Semantic Cache check
                        → Embed query text (via Model Broker L-09)
                        → Route to KB(s):
                           ├── Document KB (document_chunks)    — always queried
                           ├── Policy KB (policy_chunks)        — queried during evaluation phases and guardrail checks
                           └── Enriched KB (enriched_chunks)    — queried if web research was triggered
                        → pgvector cosine similarity search per KB
                        → Merge + re-rank results across KBs
                        → Return top-k chunks with metadata
```

---

## Embedding Model

| Parameter | Value | Notes |
|-----------|-------|-------|
| Dimension | 1536 | All VECTOR columns use 1536 dimensions |
| Model | Configured via Model Broker (L-09) | Model selection is task-based, not hardcoded |
| Index type | IVFFlat | Approximate nearest neighbour; sufficient for expected data volumes |
| Distance metric | Cosine similarity | `vector_cosine_ops` |

> Embedding model version is tracked in `token_usage_ledger` (schema.md Section 6) for versioned embeddings and drift detection.
