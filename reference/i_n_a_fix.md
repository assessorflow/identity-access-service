# Identity & Access Service — Required Fixes

Gap analysis between reference docs (`api_contract.md`, `schema.md`, `redis_store.md`, `overall.md`) and current implementation.

**Source of truth:** `/Reference-Claude-Code-Instructions/References/` (api_contract.md, schema.md, redis_store.md, overall.md)

---

## STEP 1 — MySQL → PostgreSQL

**Reference (schema.md):** Single PostgreSQL 18+ instance (Cloud SQL) + pgvector 0.8+.

**Current code:** MySQL everywhere — `pom.xml` has `mysql-connector-j` + `flyway-mysql`, migrations use MySQL syntax (`BINARY(16)`, `TINYINT(1)`, `InnoDB`, `utf8mb4_unicode_ci`, `CHANGE COLUMN`), YAML configs point to `mysql://`.

**Fix:**
- Replace `mysql-connector-j` with `org.postgresql:postgresql` in `pom.xml`
- Replace `flyway-mysql` with `flyway-database-postgresql` in `pom.xml`
- Rewrite migrations in PostgreSQL syntax:
  - `V1__init_schema.sql` — use `UUID` (native), `BOOLEAN`, `TIMESTAMPTZ`, `gen_random_uuid()`, no `InnoDB`/`utf8mb4`
  - Delete `V2__expand_session_token_column.sql` — no longer needed (schema.md says `refresh_token VARCHAR(512) UNIQUE NOT NULL`, not TEXT)
  - Add `V2__create_candidate_roster.sql` (see Step 15)
- Update all `application-*.yml`: JDBC URLs from `mysql://` to `postgresql://`, driver class, dialect
- Update `application-test.yml`: H2 `MODE=MySQL` → `MODE=PostgreSQL`

**Files affected:**
- `pom.xml`
- `src/main/resources/db/migration/V1__init_schema.sql` (rewrite)
- `src/main/resources/db/migration/V2__expand_session_token_column.sql` (delete)
- `src/main/resources/application.yml`
- `src/main/resources/application-dev.yml`
- `src/main/resources/application-docker.yml`
- `src/main/resources/application-prod.yml`
- `src/test/resources/application-test.yml`

---

## STEP 2 — gRPC Proto: Remove Old Dependency, Wire New grpc-registry

**Current code:** `pom.xml` depends on `sg.edu.nus.iss:accessorflow-proto:1.0.0-SNAPSHOT` from GCP Artifact Registry.

**Fix:**
- `grpc-registry` is now a standalone Maven project that compiles all `.proto` files and publishes a JAR (`sg.edu.nus.iss:grpc-registry:1.0.0-SNAPSHOT`) to GCP Artifact Registry (`asia-southeast1-maven.pkg.dev/accessorflow/maven-libs`)
- Replace `accessorflow-proto` dependency with `grpc-registry` in `pom.xml`
- Keep the Artifact Registry `<repository>` and `artifactregistry-maven-wagon` (still needed to pull the JAR)
- Populate `/grpc-registry/proto/assessorflow/identity/v1/identity.proto` with the `ValidateToken` contract
- Add `buf.yaml` and `buf.gen.yaml` for Python stub generation (Dale's agents)
- Add `pom.xml` to `grpc-registry` for Java stub compilation + publishing

**Publish workflow:**
```bash
cd grpc-registry
mvn clean deploy    # compiles protos → JAR → pushes to Artifact Registry
```

**Consume workflow (identity-access-service):**
```bash
mvn clean compile   # pulls grpc-registry JAR from Artifact Registry
```

**Files affected:**
- `identity-access-service/pom.xml` — swap `accessorflow-proto` → `grpc-registry`, remove unused proto properties
- `/grpc-registry/pom.xml` (new — Maven project with protobuf-maven-plugin + distributionManagement)
- `/grpc-registry/proto/assessorflow/identity/v1/identity.proto` (new)
- `/grpc-registry/buf.yaml` (populated)
- `/grpc-registry/buf.gen.yaml` (populated)

---

## STEP 3 — gRPC: Remove Extra Methods, Align ValidateToken Response

**Reference (api_contract.md §1.5):** Only 1 gRPC method for Identity service:
```
ValidateToken(token) → { valid: bool, user_id: string, role: string }
```

**Current code (`IdentityGrpcService.java`):** 3 methods — `validateToken`, `getUser`, `userExists`. Response is `UserContext` with 5 fields (userId, email, fullName, role, isActive).

**Fix:**
- Remove `getUser()` and `userExists()` methods from `IdentityGrpcService.java`
- Change `validateToken` response from `UserContext` to match contract: return `{ valid, user_id, role }` only
- Update the proto definition (Step 2) to match

**Files affected:**
- `IdentityGrpcService.java` — remove 2 methods, simplify validateToken response
- Proto definition (from Step 2)

---

## STEP 4 — JSON Response Field Naming: camelCase → snake_case

**Reference (api_contract.md):** All response fields use `snake_case` — `access_token`, `refresh_token`, `expires_in`, `full_name`, `is_active`, `created_at`.

**Current code:** Jackson defaults to `camelCase` — `accessToken`, `refreshToken`, etc.

**Fix:** Add global Jackson snake_case config:
```yaml
spring:
  jackson:
    property-naming-strategy: SNAKE_CASE
```

**Files affected:**
- `src/main/resources/application.yml`

---

## STEP 5 — Timestamps: LocalDateTime → Instant (ISO 8601 UTC)

**Reference (api_contract.md):** All timestamps must be ISO 8601 UTC: `"2026-03-21T10:00:00Z"`.

**Current code:** Uses `LocalDateTime` (no timezone). Serializes without `Z` suffix.

**Fix:**
- Change entity fields `createdAt`/`updatedAt`/`expiresAt` from `LocalDateTime` to `Instant`
- Change DTO `createdAt` fields to `Instant`
- Add Jackson config:
```yaml
spring:
  jackson:
    serialization:
      write-dates-as-timestamps: false
```

**Files affected:**
- `User.java` — `createdAt`, `updatedAt` → `Instant`
- `Session.java` — `expiresAt`, `createdAt` → `Instant`
- `UserResponse.java` — `createdAt` → `Instant`
- `AuthService.java` — session expiry calculation (use `Instant.now().plusMillis(...)`)
- `SessionRepository.java` — `deleteExpiredSessions` param type
- `application.yml` — Jackson date config

---

## STEP 6 — Sessions Table Column: `token` → `refresh_token`

**Reference (schema.md):** Column is `refresh_token VARCHAR(512) UNIQUE NOT NULL`.

**Current code:** Column is `token`, and V2 migration changed it to `TEXT` (dropping UNIQUE). The schema says it should be `VARCHAR(512) UNIQUE`.

**Fix:** Since we're rewriting V1 migration for PostgreSQL (Step 1), just use the correct column name `refresh_token` from the start. No V2 needed.

- Update `Session.java`: rename field `token` → `refreshToken`, with `@Column(name = "refresh_token", nullable = false, unique = true, length = 512)`
- Update `SessionRepository.java`: `findByToken` → `findByRefreshToken`, `deleteByToken` → `deleteByRefreshToken`
- Update all references in `AuthService.java`

**Files affected:**
- `Session.java`
- `SessionRepository.java`
- `AuthService.java`

---

## STEP 7 — Redis Cache: Align Fields and TTL

**Reference (redis_store.md §1):**
- Fields: **only** `user_id`, `workflow_id`, `role` (3 fields)
- `workflow_id`: empty string `""` (not null)
- TTL: matches session duration (7 days), evicted on logout

**Current code (`UserContextCache.java`):** 5 fields (includes `email`, `full_name`), `workflow_id` = null, TTL = 24 hours.

**Fix:**
- Remove `email` and `full_name` from cached hash
- Change `workflow_id` initial value from `null` to `""`
- Change TTL from 24 hours to 7 days (match refresh token expiry). Inject `JwtProperties` to compute from `refreshTokenExpirationMs`

**Files affected:**
- `UserContextCache.java`

---

## STEP 8 — Logout: Request Body → Authorization Header

**Reference (api_contract.md §1.3):**
```
POST /api/v1/auth/logout
Headers: Authorization: Bearer {access_token}
Response: 204 No Content
Side effect: Deletes sessions row. Evicts user:{user_id} from Redis.
```

**Current code:** Accepts `RefreshTokenRequest` body. No auth required.

**Fix:**
- Change `AuthController.logout()` to use `@AuthenticationPrincipal User user` — no request body
- Change `AuthService.logout()` to accept `UUID userId`, delete all sessions for that user
- Update `SecurityConfig` to require auth for logout (Step 10)

**Files affected:**
- `AuthController.java`
- `AuthService.java`

---

## STEP 9 — Refresh Token Response: Only Access Token + Expires

**Reference (api_contract.md §1.2):**
```json
{
  "access_token": "eyJhbG...",
  "expires_in": 3600
}
```

**Current code:** Returns full `AuthResponse` with new refresh token, user object, token_type. Also rotates the refresh token.

**Fix:**
- Create `RefreshResponse.java` DTO with only `accessToken` and `expiresIn`
- Simplify `AuthService.refreshToken()` — only generate new access token, do NOT rotate refresh token or create new session
- Update `AuthController.refresh()` return type

**Files affected:**
- New: `dto/response/RefreshResponse.java`
- `AuthService.java`
- `AuthController.java`

---

## STEP 10 — SecurityConfig: Explicit Public Paths (Logout Requires Auth)

**Reference (api_contract.md §1.3):** Logout requires `Authorization: Bearer {access_token}`.

**Current code:** All `/api/v1/auth/**` is `permitAll()`, including logout.

**Fix:** Change from wildcard to explicit public paths:
```java
.requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
```
This makes `/api/v1/auth/logout` require authentication automatically.

**Files affected:**
- `SecurityConfig.java`

---

## STEP 11 — AuthResponse: Remove `token_type` Field

**Reference (api_contract.md §1.1):** Login response: `access_token`, `refresh_token`, `expires_in`, `user`. No `token_type`.

**Current code:** Includes `tokenType = "Bearer"`.

**Fix:** Remove `tokenType` from `AuthResponse.java` and `buildAuthResponse()`.

**Files affected:**
- `AuthResponse.java`
- `AuthService.java`

---

## STEP 12 — Login Response User Object: Slim DTO (4 fields)

**Reference (api_contract.md §1.1):** Login user object has 4 fields: `id`, `email`, `full_name`, `role`.

**Current code:** Uses full `UserResponse` with 7 fields (includes `isActive`, `createdAt`, `updatedAt`).

**Fix:**
- Create `AuthUserResponse.java` with only `id`, `email`, `fullName`, `role`
- Change `AuthResponse.user` type from `UserResponse` to `AuthUserResponse`
- Update `buildAuthResponse()` mapping in `AuthService`

**Files affected:**
- New: `dto/response/AuthUserResponse.java`
- `AuthResponse.java`
- `AuthService.java`

---

## STEP 13 — Get Current User Response: Remove `updated_at`

**Reference (api_contract.md §1.4):** 6 fields: `id`, `email`, `full_name`, `role`, `is_active`, `created_at`. No `updated_at`.

**Current code (`UserResponse.java`):** Includes `updatedAt`.

**Fix:** Remove `updatedAt` from `UserResponse.java` and all `toUserResponse()` mappings.

**Files affected:**
- `UserResponse.java`
- `UserService.java`
- `AuthService.java`

---

## STEP 14 — Register Endpoint: Not in Contract

**Reference (api_contract.md §1.1–1.6):** 7 external endpoints defined. Registration is NOT among them.

**Current code:** `POST /api/v1/auth/register` exists as a public endpoint.

**Fix:** Keep it but restrict to admin-only. First admin is database-seeded.
- Add `@PreAuthorize("hasRole('ADMIN')")` to register endpoint
- Move it out of the public permit list (already handled by Step 10)
- Add to SecurityConfig: `/api/v1/auth/register` requires authentication

**Files affected:**
- `AuthController.java`
- `SecurityConfig.java` (already covered in Step 10 — just ensure register is not in the permit list)

---

## STEP 15 — Candidate Roster: New Feature

**Reference (schema.md, api_contract.md §1.6):** `candidate_roster` table + 3 REST endpoints.

**Schema:**
| Column | Type | Constraints |
|--------|------|-------------|
| id | UUID | PK |
| assessor_id | UUID | FK → users.id, NOT NULL |
| name | VARCHAR(255) | NOT NULL |
| email | VARCHAR(255) | NOT NULL |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT now() |

UNIQUE(assessor_id, email)

**Endpoints:**
- `GET /api/v1/roster` — list all entries for authenticated assessor
- `POST /api/v1/roster` — add entry (idempotent on assessor_id + email)
- `DELETE /api/v1/roster/{roster_id}` — remove entry (must be owned by assessor)

**New files:**
- `entity/CandidateRoster.java`
- `repository/CandidateRosterRepository.java`
- `service/CandidateRosterService.java`
- `controller/CandidateRosterController.java`
- `dto/request/AddRosterRequest.java`
- `dto/response/RosterResponse.java`
- `src/main/resources/db/migration/V2__create_candidate_roster.sql`

---

## STEP 16 — Extra User Endpoints: Document as Internal-Only

**Reference (api_contract.md):** Only `GET /api/v1/users/me` is in the external contract.

**Current code:** 4 extra endpoints exist: `GET /{userId}`, `PUT /{userId}`, `PATCH /{userId}/deactivate`, `PATCH /{userId}/activate`.

**Fix:** Keep them — they serve admin operations. No code change needed. Just document they are internal/admin-only and not routed through the API Gateway's public routes.

**No files affected** — documentation only.

---

## STEP 17 — Update Tests

After all fixes, update existing tests and add new ones:
- `AuthServiceTest.java` — update for new logout signature, refresh response, slim user DTO
- `JwtServiceTest.java` — update if Instant changes affect token parsing
- Add `CandidateRosterService` tests
- Update `application-test.yml` for PostgreSQL (H2 MODE=PostgreSQL)
- gRPC tests for the slimmed ValidateToken

**Files affected:**
- `src/test/java/sg/edu/nus/iss/identity/service/AuthServiceTest.java`
- `src/test/java/sg/edu/nus/iss/identity/service/JwtServiceTest.java`
- New: `src/test/java/sg/edu/nus/iss/identity/service/CandidateRosterServiceTest.java`
- `src/test/resources/application-test.yml`

---

## Summary — Execution Status

| Step | Fix | Status |
|------|-----|--------|
| 1 | MySQL → PostgreSQL | ✅ Done |
| 2 | gRPC proto: remove old dep, wire grpc-registry (published to `grpc-contracts` Artifact Registry) | ✅ Done |
| 3 | gRPC: remove extra methods, align ValidateToken (combined with Step 2) | ✅ Done |
| 4 | snake_case JSON naming | ✅ Done |
| 5 | Timestamps: Instant + ISO 8601 UTC | ✅ Done |
| 6 | Sessions column: `token` → `refresh_token` (folded into Step 1) | ✅ Done |
| 7 | Redis cache: 3 fields only, `workflow_id=""`, TTL=7 days | ✅ Done |
| 8 | Logout via `@AuthenticationPrincipal` (no body) | ✅ Done |
| 9 | Refresh returns only `RefreshResponse` (access_token + expires_in) | ✅ Done |
| 10 | SecurityConfig: only `/auth/login` + `/auth/refresh` public | ✅ Done |
| 11 | Remove `token_type` from AuthResponse | ✅ Done |
| 12 | Login user object: slim `AuthUserResponse` (4 fields) | ✅ Done |
| 13 | Remove `updated_at` from UserResponse | ✅ Done |
| 14 | Register: admin-only (`@PreAuthorize`) + `@EnableMethodSecurity` | ✅ Done |
| 15 | Candidate Roster: entity, repo, service, controller, migration | ✅ Done |
| 16 | Extra user endpoints: document as internal-only (no code change) | ✅ N/A |
| 17 | Update tests: AuthServiceTest + new CandidateRosterServiceTest | ✅ Done |

> **Also done (not originally in the fix list):**
> - Deleted root `Dockerfile` (kept `Dockerfile.ci` in `.github/workflows/`)
> - Created `grpc-registry/pom.xml` for building + publishing proto stubs JAR
> - Created `grpc-registry/proto/assessorflow/identity/v1/identity.proto` (ValidateToken contract)
> - Populated `grpc-registry/buf.yaml` and `buf.gen.yaml`
> - Updated Terraform `infra/terraform/modules/artifact-registry/` — renamed `maven-libs` → `grpc-contracts`, fixed duplicate resource blocks, fixed orphaned state
> - Updated Terraform `infra/artifact-registry/registry.tf` (legacy standalone config) — same rename
> - Published `sg.edu.nus.iss:grpc-registry:1.0.0-SNAPSHOT` to GCP Artifact Registry (`asia-southeast1-maven.pkg.dev/accessorflow/grpc-contracts`)
