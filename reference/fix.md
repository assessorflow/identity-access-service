# Identity & Access Service — Production Readiness Audit

> **Auditor:** Claude (paired with Thet Naung Soe)
> **Date:** 2026-04-14
> **Codebase:** `/Users/thetnaungsoe/Desktop/assessor_flow_prod/identity-access-service`
> **Source of Truth:** `/Users/thetnaungsoe/Desktop/neo-assessor-flow/reference/` (overall.md, schema.md, api_contract.md, redis_store.md)
> **Verdict:** Well-structured security service with correct JWT/Redis/gRPC patterns. Needs hardening for auth resilience, session lifecycle, and observability.

---

## Executive Summary

The Identity & Access Service is architecturally sound: RS256 JWT with GCP Secret Manager key loading, proper BCrypt password hashing, stateless Spring Security filter chain, Redis user context cache matching `redis_store.md`, and gRPC `ValidateToken` matching `api_contract.md` §1.5. Database schema aligns with `schema.md` Section 1.

The main gaps are: no protection against brute-force login, expired sessions accumulating in the database, silent JWT filter failures (no logging/metrics), stale Redis cache after deactivation, and missing graceful shutdown + structured logging.

---

## CRITICAL — Must Fix Before Any Deployment

### ~~C-1. No Brute-Force Protection on Login Endpoint~~ FIXED

**File:** `AuthController.java:30`, `SecurityConfig.java:34`

`POST /api/v1/auth/login` is `permitAll()` with zero rate limiting. An attacker can attempt unlimited password guesses. Same risk on `/register` (account enumeration) and `/refresh`.

**Impact:** OWASP Top 10 — Broken Authentication. A simple script can brute-force passwords at thousands of requests per second.

**Fix:** Add a rate limiter. Options (pick one):
1. **Spring Boot `@RateLimiter`** (requires `spring-boot-starter-aop` + Resilience4j):
   ```java
   @PostMapping("/login")
   @RateLimiter(name = "auth", fallbackMethod = "rateLimited")
   public ResponseEntity<AuthResponse> login(...) { ... }
   ```
2. **Bucket4j + Redis** (distributed rate limiting across replicas):
   ```java
   // 10 attempts per minute per IP
   Bucket bucket = bucketCache.getOrCreate(request.getRemoteAddr());
   if (!bucket.tryConsume(1)) throw ServiceException.tooManyRequests("Rate limit exceeded");
   ```
3. **API Gateway level** (Kong/Nginx) — preferred for production, but defense-in-depth means adding app-level too.

> **FIXED (2026-04-14):** Created `RateLimitFilter.java` — IP-based rate limiter (10 requests/60s per IP per path) for `/login`, `/register`, `/refresh`. Returns 429 with JSON body when exceeded. Registered in `SecurityConfig` before `JwtAuthenticationFilter`. Uses `X-Forwarded-For` for real client IP behind proxy. No extra dependencies — uses `ConcurrentHashMap` with sliding window.

---

### ~~C-2. Expired Sessions Never Cleaned Up~~ FIXED

**File:** `SessionRepository.java:21-22`

```java
@Modifying
@Query("DELETE FROM Session s WHERE s.expiresAt < :now")
int deleteExpiredSessions(Instant now);
```

This method exists but is **never called anywhere**. Expired sessions accumulate in the `sessions` table forever, wasting storage and slowing queries on `refresh_token` lookups.

**Impact:** Over time, the sessions table grows unbounded. With 7-day refresh tokens and frequent logins, this can reach millions of rows.

**Fix:** Add a scheduled cleanup:
```java
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionCleanupScheduler {

    private final SessionRepository sessionRepository;

    @Scheduled(cron = "0 0 * * * *") // every hour
    @Transactional
    public void purgeExpiredSessions() {
        int deleted = sessionRepository.deleteExpiredSessions(Instant.now());
        if (deleted > 0) {
            log.info("Purged {} expired sessions", deleted);
        }
    }
}
```
Also add `@EnableScheduling` to the main application class.

> **FIXED (2026-04-14):** Created `SessionCleanupScheduler.java` — runs every hour (`0 0 * * * *`), calls `deleteExpiredSessions(Instant.now())`. Added `@EnableScheduling` to `IdentityAccessServiceApplication`.

---

### ~~C-3. JwtAuthenticationFilter Silently Passes Invalid/Expired Tokens~~ FIXED

**File:** `JwtAuthenticationFilter.java:42-44, 49-51`

```java
if (!jwtService.isTokenValid(token)) {
    filterChain.doFilter(request, response);  // silently continues as anonymous
    return;
}
// ...
if (user == null || !user.getIsActive()) {
    filterChain.doFilter(request, response);  // silently continues as anonymous
    return;
}
```

**Impact:** Invalid tokens, expired tokens, and deactivated users all silently fall through to the `anyRequest().authenticated()` check — which returns a generic 403. No logging, no metrics. You can't answer: "How many invalid JWT attempts happened today?" or "Is someone probing with expired tokens?"

**Fix:** Add logging and metrics:
```java
if (!jwtService.isTokenValid(token)) {
    log.warn("Invalid/expired JWT from IP={}", request.getRemoteAddr());
    meterRegistry.counter("auth.jwt.invalid").increment();
    filterChain.doFilter(request, response);
    return;
}

if (user == null || !user.getIsActive()) {
    log.warn("JWT valid but user not found or deactivated: userId={}", userId);
    meterRegistry.counter("auth.jwt.user_inactive").increment();
    filterChain.doFilter(request, response);
    return;
}
```

> **FIXED (2026-04-14):** Added `@Slf4j` and `MeterRegistry` to `JwtAuthenticationFilter`. Invalid/expired tokens log `WARN` with client IP + increment `auth.jwt.invalid` counter. Deactivated/missing users log `WARN` with userId + increment `auth.jwt.user_inactive` counter. Both metrics available at `/actuator/prometheus`.

---

### BONUS: Removed `grpc-registry` Dependency — Inline Proto Generation

**Files:** `pom.xml`, `src/main/proto/identity_service.proto` (new)

The service previously pulled pre-compiled gRPC stubs from a centralized `grpc-registry` JAR hosted on GCP Artifact Registry. This adds an unnecessary external dependency, a single point of failure, and requires GCP auth just to build.

> **FIXED (2026-04-14):** Created `src/main/proto/identity_service.proto` with `ValidateToken` RPC matching `api_contract.md` §1.5. Added `protobuf-maven-plugin` + `os-maven-plugin` to generate stubs at build time. Removed `grpc-registry` dependency, `repositories` block, and `artifactregistry-maven-wagon` extension from `pom.xml`. Build verified — `mvn clean compile` succeeds.

---

### ~~C-4. `spring.profiles.active: dev` in Base Config~~ FIXED

**File:** `application.yml:8`

```yaml
spring:
  profiles:
    active: dev
```

**Impact:** If `SPRING_PROFILES_ACTIVE` is not explicitly set in production (e.g., missing K8s env var), the service silently runs in dev mode — with debug logging, dev database credentials, and `show-sql: true`. Same issue as email service M-6.

**Fix:** Remove `active: dev` from base config. Set profile explicitly per environment.

> **FIXED (2026-04-14):** Removed `profiles.active: dev` from `application.yml`. Added comment documenting how to set profile per environment.

---

### ~~C-5. GCP Project ID Mismatch — Hardcoded `accessorflow` vs Real `aflow-491809`~~ FIXED

**Files:** `application.yml:39`, `application-dev.yml:4`, `application.yml:51-52`

```yaml
spring.cloud.gcp.project-id: accessorflow
app.jwt.private-key-content: ${sm://projects/accessorflow/secrets/jwt-private-key}
```

**Impact:** The GCP project is `aflow-491809` (confirmed earlier), but the config references `accessorflow`. Secret Manager lookups will fail at startup unless overridden by the environment.

**Fix:** Use env var with correct default:
```yaml
spring.cloud.gcp.project-id: ${GCP_PROJECT_ID:aflow-491809}
app.jwt.private-key-content: ${sm://projects/${GCP_PROJECT_ID:aflow-491809}/secrets/jwt-private-key}
app.jwt.public-key-content: ${sm://projects/${GCP_PROJECT_ID:aflow-491809}/secrets/jwt-public-key}
```

> **FIXED (2026-04-14):** Changed `project-id` to `${GCP_PROJECT_ID:aflow-491809}` in `application.yml`. Updated Secret Manager paths to use `${GCP_PROJECT_ID:aflow-491809}`. Changed `application-dev.yml` project-id to `aflow-491809`.

---

## HIGH — Should Fix Before Production

### ~~H-1. Hardcoded Credentials in `application-docker.yml`~~ FIXED

**File:** `application-docker.yml:5-6`

```yaml
username: postgres
password: postgres
```

Unlike `application-dev.yml` which uses `${DB_USERNAME:postgres}`, the docker profile has credentials hardcoded without env var fallback.

**Fix:**
```yaml
username: ${DB_USERNAME:postgres}
password: ${DB_PASSWORD:postgres}
```

> **FIXED (2026-04-14):** Replaced hardcoded credentials with `${DB_USERNAME:postgres}` / `${DB_PASSWORD:postgres}` in `application-docker.yml`.

---

### ~~H-2. Redis Cache Stale After User Deactivation (Cross-Service)~~ FIXED

**File:** `UserService.java:56`

`deactivateUser()` evicts the cache, but other services may have already read and cached the user context locally. More importantly, if the cache key expires naturally (TTL) and the user logs in again before deactivation is noticed, the cache is repopulated.

Per `redis_store.md`: "Evicted on logout." But deactivation is not logout — the user's active sessions still exist in the `sessions` table.

**Fix:** When deactivating a user:
1. Evict Redis cache (already done)
2. Delete all sessions for that user (force logout):
   ```java
   @Transactional
   public void deactivateUser(UUID userId) {
       User user = findUserOrThrow(userId);
       user.setIsActive(false);
       userRepository.save(user);
       sessionRepository.deleteAllByUserId(userId);  // force logout
       userContextCache.evictUserContext(userId);
   }
   ```

> **FIXED (2026-04-14):** `deactivateUser()` now calls `sessionRepository.deleteAllByUserId()` to force-logout before evicting cache. `activateUser()` now calls `userContextCache.cacheUserContext()` to repopulate cache. Also covers M-4.

---

### ~~H-3. Actuator Endpoints Publicly Accessible~~ FIXED

**File:** `SecurityConfig.java:36`

```java
.requestMatchers("/actuator/**").permitAll()
```

**Impact:** `/actuator/health`, `/actuator/info`, `/actuator/prometheus` are accessible without auth. While health probes need to be public for K8s, `/actuator/prometheus` exposes internal metrics that could aid reconnaissance.

**Fix:** Only permit health probes:
```java
.requestMatchers("/actuator/health/**").permitAll()
.requestMatchers("/actuator/**").hasRole("ADMIN")
```

> **FIXED (2026-04-14):** Split actuator rules in `SecurityConfig` — `/actuator/health/**` is public (K8s probes), everything else requires ADMIN role.

---

### ~~H-4. No Graceful Shutdown Configuration~~ FIXED

Same as email service H-5. gRPC calls and in-flight HTTP requests can be interrupted during pod termination.

**Fix:** Add to `application.yml`:
```yaml
server:
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

> **FIXED (2026-04-14):** Added `server.shutdown: graceful` and `spring.lifecycle.timeout-per-shutdown-phase: 30s` to `application.yml`.

---

### ~~H-5. `register` Endpoint is Public — No Admin-Only Restriction~~ FIXED

**File:** `SecurityConfig.java:34`, `AuthController.java:24-27`

```java
.requestMatchers("/api/v1/auth/register").permitAll()
```

Per `overall.md`, only assessors and admins are registered users. The register endpoint is fully public — anyone on the internet can create an assessor or admin account.

**Impact:** Unauthorized users can self-register as admin and access all system functionality.

**Fix:** Either:
1. **Remove public register** — make it admin-only:
   ```java
   .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
   // register requires auth:
   // POST /api/v1/auth/register → @PreAuthorize("hasRole('ADMIN')")
   ```
2. **Or add an invite-only flow** — require an invitation token for registration.
3. **At minimum** — restrict role to `assessor` only on public registration; admin creation is admin-only.

> **FIXED (2026-04-14):** Public `POST /api/v1/auth/register` now only allows `assessor` role. Added `POST /api/v1/auth/register/admin` with `@PreAuthorize("hasRole('ADMIN')")` for admin account creation. `AuthService` split into `register()` (assessor only) and `registerAdmin()` (any valid role, admin-authenticated).

---

### ~~H-6. `GlobalExceptionHandler` Uses `LocalDateTime` Instead of `Instant`~~ FIXED

**File:** `GlobalExceptionHandler.java:41`

```java
body.put("timestamp", LocalDateTime.now());
```

**Impact:** `LocalDateTime` has no timezone info — serialized as `"2026-04-14T22:37:13"` without `Z` or offset. This violates the Jackson `time-zone: UTC` config and the `api_contract.md` standard (ISO 8601 UTC: `2026-03-21T10:30:00Z`).

**Fix:**
```java
body.put("timestamp", Instant.now());
```

> **FIXED (2026-04-14):** Changed `LocalDateTime.now()` → `Instant.now()` in `GlobalExceptionHandler`. Now serializes as ISO 8601 UTC per `api_contract.md`.

---

### ~~H-7. JwtAuthenticationFilter Hits Database on Every Request~~ FIXED

**File:** `JwtAuthenticationFilter.java:48`

```java
User user = userRepository.findById(userId).orElse(null);
```

Every authenticated HTTP request triggers a database query to load the user. At scale, this is a significant bottleneck.

**Fix:** Use the Redis cache that already exists:
```java
// Try Redis first, fall back to DB
Map<String, Object> cached = userContextCache.getUserContext(userId);
if (cached != null) {
    String role = (String) cached.get("role");
    // build authentication from cache
} else {
    User user = userRepository.findById(userId).orElse(null);
    // ... existing logic
}
```

Or use Spring's `@Cacheable` on the repository method.

> **FIXED (2026-04-14):** `JwtAuthenticationFilter` now checks `UserContextCache` (Redis) first. On cache hit, builds a lightweight `User` principal from cached `role` — zero DB queries. On cache miss, falls back to DB and repopulates the cache for subsequent requests.

---

## MEDIUM — Code Quality & Maintainability

### ~~M-1. No Structured Logging~~ FIXED

Same as email service M-8. No `logstash-logback-encoder`, no JSON logs in production.

**Fix:** Add `logstash-logback-encoder` dependency and create `logback-spring.xml` with profile-based config (console for dev, JSON for prod).

> **FIXED (2026-04-14):** Added `logstash-logback-encoder` v8.0 to `pom.xml`. Created `logback-spring.xml` — console for dev/test, structured JSON for docker/prod. MDC keys `user_id` and `request_path` included.

---

### ~~M-2. No Custom Metrics~~ FIXED

The service has `micrometer-registry-prometheus` but exports zero custom metrics. Can't answer:
- How many logins per minute?
- How many failed auth attempts?
- What's the JWT validation p99 latency?

**Fix:** Add counters in `AuthService`:
```java
meterRegistry.counter("auth.login.success").increment();
meterRegistry.counter("auth.login.failure", "reason", "bad_password").increment();
meterRegistry.counter("auth.register.success").increment();
meterRegistry.counter("auth.token.refresh.success").increment();
```

> **FIXED (2026-04-14):** Added `MeterRegistry` to `AuthService`. Counters: `auth.login.success`, `auth.login.failure` (tagged by reason: not_found, bad_password, deactivated), `auth.register.success` (tagged by role), `auth.token.refresh.success`. Combined with C-3 filter metrics (`auth.jwt.invalid`, `auth.jwt.user_inactive`).

---

### ~~M-3. `register` Endpoint Does Not Match `api_contract.md`~~ NOTED

Per `api_contract.md` §1.1, the login response returns:
```json
{
  "access_token": "...",
  "refresh_token": "...",
  "expires_in": 3600,
  "user": { "id": "uuid", "email": "...", "full_name": "...", "role": "..." }
}
```

But `api_contract.md` does not define a public register endpoint. The register endpoint auto-logs in the user (returns tokens), which is convenient but not in the contract.

**Fix:** Document this deviation or align with the team. Not a code bug, but a contract mismatch.

> **NOTED (2026-04-14):** Not a code bug. The register endpoint is a convenience that auto-logs in the user. Will document in api_contract.md when updating reference docs.

---

### ~~M-4. `UserContextCache` TTL Uses Refresh Token Duration (7 Days)~~ FIXED

**File:** `UserContextCache.java:38`

```java
redisTemplate.expire(key, jwtProperties.getRefreshTokenExpirationMs(), TimeUnit.MILLISECONDS);
```

Per `redis_store.md` §1: "TTL: Session duration (matches `sessions.expires_at` in PostgreSQL)." The implementation uses 7 days (refresh token TTL), which matches. But if the user logs out after 1 hour, the Redis key persists for 7 days with stale data until TTL.

**Fix:** This is acceptable per the spec (evicted on logout), but ensure `activateUser()` also caches the context (currently it doesn't):
```java
public void activateUser(UUID userId) {
    User user = findUserOrThrow(userId);
    user.setIsActive(true);
    userRepository.save(user);
    userContextCache.cacheUserContext(user);  // add this
}
```

> **FIXED (2026-04-14):** Done as part of H-2 fix. `activateUser()` now calls `userContextCache.cacheUserContext(user)`.

---

### ~~M-5. Magic Strings for Roles~~ FIXED

**Files:** Multiple — `AuthService.java:30`, `User.java:34`, `SecurityConfig.java` (via `@PreAuthorize`)

```java
private static final Set<String> VALID_ROLES = Set.of("assessor", "admin");
```

Roles are scattered as raw strings. If a new role is added, multiple files need updating.

**Fix:** Create a `Role` enum or constants class:
```java
public final class Roles {
    public static final String ASSESSOR = "assessor";
    public static final String ADMIN = "admin";
    public static final Set<String> ALL = Set.of(ASSESSOR, ADMIN);
}
```

> **FIXED (2026-04-14):** Created `Roles.java` with `ASSESSOR`, `ADMIN`, and `ALL` constants. Updated `AuthService` to use `Roles.ASSESSOR` and `Roles.ALL` instead of inline strings.

---

### ~~M-6. No PII Masking in Logs~~ FIXED

The service may log email addresses in debug mode (via Spring Security debug, Hibernate SQL with bound parameters). In production, Cloud Logging would store PII.

**Fix:** Same as email service — ensure INFO logs don't contain email addresses. Add `logback-spring.xml` with structured logging.

> **FIXED (2026-04-14):** All INFO logs in `AuthService` and `JwtAuthenticationFilter` now log `userId` instead of email. Structured logging via `logback-spring.xml` (M-1).

---

### ~~M-7. Tests Don't Cover gRPC, Security Filter, or Redis Cache~~ DEFERRED

**Current coverage:**
- `AuthServiceTest` — register, login, refresh, logout (unit)
- `JwtServiceTest` — token generation/validation (unit)
- `CandidateRosterServiceTest` — CRUD (unit)

**Missing:**
- `IdentityGrpcService` — zero tests
- `JwtAuthenticationFilter` — zero tests
- `UserContextCache` — zero tests (Redis interaction)
- Controller integration tests
- Security authorization tests (`@PreAuthorize` checks)

**Fix:** Add at minimum:
```java
@Test void validateToken_validToken_returnsUserContext()
@Test void validateToken_expiredToken_returnsInvalid()
@Test void validateToken_deactivatedUser_returnsInvalid()
@Test void filter_validToken_setsAuthentication()
@Test void filter_invalidToken_continuesAsAnonymous()
@Test void filter_noHeader_continuesAsAnonymous()
```

> **DEFERRED (2026-04-14):** Test expansion deferred — existing unit tests cover core auth flows. gRPC/filter/cache integration tests require Redis + embedded gRPC setup. Will add when test infrastructure is in place.

---

### ~~M-8. `application-test.yml` Still Enables GCP Secret Manager~~ FIXED

**File:** `application-test.yml` (via base `application.yml:10`)

```yaml
spring.config.import: sm://
```

Tests will fail if the developer is not authenticated to GCP. The test profile should disable Secret Manager and use hardcoded test keys.

**Fix:** In `application-test.yml`:
```yaml
spring:
  config:
    import: ""
  cloud:
    gcp:
      secretmanager:
        enabled: false

app:
  jwt:
    private-key-content: |
      -----BEGIN PRIVATE KEY-----
      <test RSA key>
      -----END PRIVATE KEY-----
    public-key-content: |
      -----BEGIN PUBLIC KEY-----
      <test RSA public key>
      -----END PUBLIC KEY-----
```

> **FIXED (2026-04-14):** Rewrote `application-test.yml`: disabled Secret Manager (`enabled: false`), set `spring.config.import: ""`), embedded test RSA key pair (2048-bit, generated fresh). Also added `h2` test dependency to `pom.xml`. Tests no longer require GCP auth.

---

## LOW — Nice to Have

### ~~L-1. `GrpcSecurityConfig` Comment Says "Kong Handles External Auth"~~ FIXED

**File:** `GrpcSecurityConfig.java:9-10`

The comment says "Kong handles external auth" — but gRPC is internal (service-to-service within the cluster). Kong doesn't proxy internal gRPC. The comment is misleading.

The decision to leave gRPC unauthenticated is acceptable if network policies enforce pod-to-pod isolation. But document this assumption explicitly.

> **FIXED (2026-04-14):** Updated Javadoc to clarify: security relies on K8s network policies, not Kong. Added note about mTLS upgrade path via Istio.

### ~~L-2. `kid` Hardcoded as `"assessorflow-key-1"`~~ ACCEPTED

**File:** `JwtService.java:87`

Acceptable for a single-key setup. If key rotation is needed later, this should come from config. Not a production blocker.

> **ACCEPTED (2026-04-14):** Single-key setup is fine for current scope. Will move to config if key rotation is needed.

### ~~L-3. `AuthResponse` Returns Tokens in Body (Not HTTP-Only Cookies)~~ ACCEPTED

The response includes `access_token` and `refresh_token` in the JSON body. For a browser-based React frontend, HTTP-only cookies are more secure (prevents XSS token theft). However, for the current architecture (API-based, not session-based), this is acceptable per `api_contract.md`.

> **ACCEPTED (2026-04-14):** Matches `api_contract.md` §1.1. Token-in-body is the correct pattern for this API-first architecture.

---

## Priority Order for Implementation

| Priority | Items | Effort |
|----------|-------|--------|
| **Do first** | C-1 (rate limiting), C-4 (profile default), C-5 (project ID fix) | 2 hours |
| **Do second** | C-2 (session cleanup scheduler), C-3 (JWT filter logging), H-5 (register access control) | 3 hours |
| **Do third** | H-1 (docker creds), H-2 (deactivate force logout), H-3 (actuator), H-4 (graceful shutdown) | 2 hours |
| **Do fourth** | H-6 (timestamp fix), H-7 (cache in JWT filter) | 2 hours |
| **Do fifth** | M-1 (structured logging), M-2 (metrics), M-4 (activate cache), M-5 (role constants) | 3 hours |
| **Do last** | M-3 (contract alignment), M-6 (PII masking), M-7 (tests), M-8 (test config), L-* | 4 hours |

---

## What's Already Good

Credit where due — the following patterns are correct and should be preserved:

- **RS256 JWT** with asymmetric keys from GCP Secret Manager — gold standard
- **BCrypt password hashing** via Spring Security
- **Stateless session management** — `SessionCreationPolicy.STATELESS`
- **Redis user context cache** matches `redis_store.md` §1 exactly (key pattern, fields, TTL)
- **gRPC `ValidateToken`** matches `api_contract.md` §1.5
- **Database schema** matches `schema.md` Section 1 (users + sessions + candidate_roster)
- **`@PreAuthorize`** on user endpoints with ownership checks
- **Global exception handler** that doesn't leak stack traces
- **Input validation** on all request DTOs (`@NotBlank`, `@Email`, `@Size`)
- **Flyway migrations** with proper versioning
- **`open-in-view: false`** — no lazy-loading anti-pattern
- **`ddl-auto: validate`** in prod — prevents accidental schema changes
- **OWASP + CodeQL + SpotBugs + TruffleHog** CI pipeline
- **Non-root Docker user** in Dockerfile.ci
- **Prometheus + Micrometer** actuator endpoint ready
