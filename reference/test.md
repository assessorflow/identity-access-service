
# Identity & Access Service — Test Plan

> **Prerequisites:**
> - PostgreSQL running on `localhost:5432` with database `identity_access_db`
> - Redis running on `localhost:6379`
> - GCP auth for Secret Manager: `gcloud auth application-default login`
> - Run: `cd identity-access-service && mvn clean package -DskipTests && java -Dspring.profiles.active=dev -jar target/identity-access-service-1.0.0-SNAPSHOT.jar`
> - Service runs on `http://localhost:8081` (HTTP) + `localhost:9090` (gRPC)

---

## 1. Auth — Register (Public)

### 1.1 Register Assessor (should succeed)

```
POST http://localhost:8081/api/v1/auth/register
Content-Type: application/json

{
  "email": "assessor1@test.com",
  "password": "Test1234!",
  "full_name": "Test Assessor",
  "role": "assessor"
}
```

**Expected:** `201 Created`
```json
{
  "access_token": "eyJ...",
  "refresh_token": "eyJ...",
  "expires_in": 900,
  "user": {
    "id": "uuid",
    "email": "assessor1@test.com",
    "full_name": "Test Assessor",
    "role": "assessor"
  }
}
```

**Verify Redis:**
```bash
redis-cli HGETALL "user:<user_id_from_response>"
```
Expected: `user_id`, `workflow_id` (empty string), `role` = `assessor`

---

### 1.2 Register Admin via Public Endpoint (should FAIL)

```
POST http://localhost:8081/api/v1/auth/register
Content-Type: application/json

{
  "email": "admin@test.com",
  "password": "Test1234!",
  "full_name": "Evil Admin",
  "role": "admin"
}
```

**Expected:** `400 Bad Request`
```json
{
  "message": "Public registration only allows 'assessor' role"
}
```

---

### 1.3 Register Duplicate Email (should FAIL)

```
POST http://localhost:8081/api/v1/auth/register
Content-Type: application/json

{
  "email": "assessor1@test.com",
  "password": "Different1!",
  "full_name": "Another Assessor",
  "role": "assessor"
}
```

**Expected:** `409 Conflict`
```json
{
  "message": "Email already registered"
}
```

---

### 1.4 Register with Invalid Email (should FAIL)

```
POST http://localhost:8081/api/v1/auth/register
Content-Type: application/json

{
  "email": "not-an-email",
  "password": "Test1234!",
  "full_name": "Bad Email",
  "role": "assessor"
}
```

**Expected:** `400 Bad Request` — validation error

---

### 1.5 Register with Short Password (should FAIL)

```
POST http://localhost:8081/api/v1/auth/register
Content-Type: application/json

{
  "email": "short@test.com",
  "password": "123",
  "full_name": "Short Pass",
  "role": "assessor"
}
```

**Expected:** `400 Bad Request` — "Password must be between 8 and 128 characters"

---

## 2. Auth — Login

### 2.1 Login Success

```
POST http://localhost:8081/api/v1/auth/login
Content-Type: application/json

{
  "email": "assessor1@test.com",
  "password": "Test1234!"
}
```

**Expected:** `200 OK` — returns `access_token`, `refresh_token`, `expires_in`, `user`

**Save the `access_token` and `refresh_token` for subsequent tests.**

**Verify Redis:**
```bash
redis-cli HGETALL "user:<user_id>"
```

---

### 2.2 Login Wrong Password (should FAIL)

```
POST http://localhost:8081/api/v1/auth/login
Content-Type: application/json

{
  "email": "assessor1@test.com",
  "password": "WrongPassword!"
}
```

**Expected:** `401 Unauthorized` — "Invalid email or password"

---

### 2.3 Login Non-Existent Email (should FAIL)

```
POST http://localhost:8081/api/v1/auth/login
Content-Type: application/json

{
  "email": "nobody@test.com",
  "password": "Test1234!"
}
```

**Expected:** `401 Unauthorized` — "Invalid email or password"

---

## 3. Auth — Token Refresh

### 3.1 Refresh Token Success

```
POST http://localhost:8081/api/v1/auth/refresh
Content-Type: application/json

{
  "refresh_token": "<refresh_token_from_login>"
}
```

**Expected:** `200 OK`
```json
{
  "access_token": "eyJ...(new token)",
  "expires_in": 900
}
```

---

### 3.2 Refresh with Invalid Token (should FAIL)

```
POST http://localhost:8081/api/v1/auth/refresh
Content-Type: application/json

{
  "refresh_token": "invalid-token-string"
}
```

**Expected:** `401 Unauthorized` — "Invalid refresh token"

---

## 4. Auth — Logout

### 4.1 Logout Success

```
POST http://localhost:8081/api/v1/auth/logout
Authorization: Bearer <access_token>
```

**Expected:** `204 No Content`

**Verify Redis — cache evicted:**
```bash
redis-cli EXISTS "user:<user_id>"
```
Expected: `(integer) 0`

**Verify DB — sessions deleted:**
```sql
SELECT * FROM sessions WHERE user_id = '<user_id>';
```
Expected: no rows

**After logout, re-login for remaining tests.**

---

## 5. User Management

### 5.1 Get Current User

```
GET http://localhost:8081/api/v1/users/me
Authorization: Bearer <access_token>
```

**Expected:** `200 OK`
```json
{
  "id": "uuid",
  "email": "assessor1@test.com",
  "full_name": "Test Assessor",
  "role": "assessor",
  "is_active": true,
  "created_at": "2026-..."
}
```

---

### 5.2 Get User by ID (own ID)

```
GET http://localhost:8081/api/v1/users/<your_user_id>
Authorization: Bearer <access_token>
```

**Expected:** `200 OK` — same as above

---

### 5.3 Get Another User's Profile (should FAIL unless admin)

```
GET http://localhost:8081/api/v1/users/00000000-0000-0000-0000-000000000000
Authorization: Bearer <access_token>
```

**Expected:** `403 Forbidden` — assessor can't view other users

---

### 5.4 Update Own Profile

```
PUT http://localhost:8081/api/v1/users/<your_user_id>
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "full_name": "Updated Name"
}
```

**Expected:** `200 OK` — returns updated user

**Verify Redis — cache updated:**
```bash
redis-cli HGETALL "user:<user_id>"
```

---

### 5.5 Update Password

```
PUT http://localhost:8081/api/v1/users/<your_user_id>
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "password": "NewPass1234!"
}
```

**Expected:** `200 OK`

**Verify:** Login with old password should fail, new password should work.

---

## 6. JWT & JWKS

### 6.1 JWKS Endpoint

```
GET http://localhost:8081/.well-known/jwks.json
```

**Expected:** `200 OK` — no auth required
```json
{
  "keys": [
    {
      "kty": "RSA",
      "alg": "RS256",
      "use": "sig",
      "kid": "assessorflow-key-1",
      "n": "...",
      "e": "AQAB"
    }
  ]
}
```

---

### 6.2 Access Protected Endpoint Without Token (should FAIL)

```
GET http://localhost:8081/api/v1/users/me
```

**Expected:** `403 Forbidden`

---

### 6.3 Access Protected Endpoint With Invalid Token (should FAIL)

```
GET http://localhost:8081/api/v1/users/me
Authorization: Bearer invalid.token.here
```

**Expected:** `403 Forbidden`

**Verify Prometheus metric incremented:**
```
GET http://localhost:8081/actuator/prometheus
```
Search for `auth_jwt_invalid_total` — should be > 0

---

## 7. Rate Limiting

### 7.1 Trigger Rate Limit on Login

Send 11 rapid requests:

```bash
for i in $(seq 1 11); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST http://localhost:8081/api/v1/auth/login \
    -H "Content-Type: application/json" \
    -d '{"email":"assessor1@test.com","password":"wrong"}';
done
```

**Expected:** First 10 return `401`, 11th returns `429 Too Many Requests`
```json
{
  "status": 429,
  "error": "Too Many Requests",
  "message": "Rate limit exceeded. Try again later."
}
```

**Wait 60 seconds, then retry — should work again.**

---

## 8. Redis Cache Verification

### 8.1 Cache Created on Login

```bash
# Login first, then:
redis-cli HGETALL "user:<user_id>"
```

**Expected:**
```
1) "user_id"
2) "<uuid>"
3) "workflow_id"
4) ""
5) "role"
6) "assessor"
```

---

### 8.2 Cache TTL Set

```bash
redis-cli TTL "user:<user_id>"
```

**Expected:** ~604800 seconds (7 days = refresh token TTL)

---

### 8.3 Cache Evicted on Logout

```bash
# Logout via API, then:
redis-cli EXISTS "user:<user_id>"
```

**Expected:** `(integer) 0`

---

### 8.4 Cache Updated on Profile Update

```bash
# Update full_name via PUT, then:
redis-cli HGETALL "user:<user_id>"
```

**Expected:** cache still exists (was repopulated)

---

## 9. Admin Operations

### 9.1 Bootstrap Admin Account

For a fresh system, seed an admin directly in the DB:

```sql
-- First register a normal assessor via API to get a proper bcrypt hash,
-- then update their role to admin:
UPDATE users SET role = 'admin' WHERE email = 'assessor1@test.com';
```

Then login as that user — they now have ADMIN role. Or register a second assessor and promote them.

---

### 9.2 Admin Registers Another Admin

```
POST http://localhost:8081/api/v1/auth/register/admin
Authorization: Bearer <admin_access_token>
Content-Type: application/json

{
  "email": "admin2@test.com",
  "password": "Admin1234!",
  "full_name": "Second Admin",
  "role": "admin"
}
```

**Expected:** `201 Created`

---

### 9.3 Admin Deactivates User

```
PATCH http://localhost:8081/api/v1/users/<assessor_user_id>/deactivate
Authorization: Bearer <admin_access_token>
```

**Expected:** `204 No Content`

**Verify — all sessions deleted + cache evicted:**
```bash
redis-cli EXISTS "user:<assessor_user_id>"
```
Expected: `(integer) 0`

```sql
SELECT * FROM sessions WHERE user_id = '<assessor_user_id>';
```
Expected: no rows

**Verify — deactivated user can't login:**
```
POST http://localhost:8081/api/v1/auth/login
{"email": "assessor1@test.com", "password": "Test1234!"}
```
Expected: `403 Forbidden` — "Account is deactivated"

---

### 9.4 Admin Activates User

```
PATCH http://localhost:8081/api/v1/users/<assessor_user_id>/activate
Authorization: Bearer <admin_access_token>
```

**Expected:** `204 No Content`

**Verify — cache repopulated:**
```bash
redis-cli HGETALL "user:<assessor_user_id>"
```
Expected: `user_id`, `workflow_id`, `role` fields present

**Verify — user can login again.**

---

### 9.5 Assessor Tries to Deactivate (should FAIL)

```
PATCH http://localhost:8081/api/v1/users/<any_user_id>/deactivate
Authorization: Bearer <assessor_access_token>
```

**Expected:** `403 Forbidden` — only ADMIN role allowed

---

## 10. Candidate Roster

### 10.1 Add Roster Entry

```
POST http://localhost:8081/api/v1/roster
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "name": "Student One",
  "email": "student1@test.com"
}
```

**Expected:** `201 Created`

---

### 10.2 List Roster

```
GET http://localhost:8081/api/v1/roster
Authorization: Bearer <access_token>
```

**Expected:** `200 OK` — returns list of roster entries for the authenticated assessor

---

### 10.3 Add Duplicate Roster Entry (should FAIL)

```
POST http://localhost:8081/api/v1/roster
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "name": "Student One Again",
  "email": "student1@test.com"
}
```

**Expected:** `409 Conflict` — duplicate (assessor_id, email) constraint

---

### 10.4 Delete Roster Entry

```
DELETE http://localhost:8081/api/v1/roster/<roster_id>
Authorization: Bearer <access_token>
```

**Expected:** `204 No Content`

---

## 11. Actuator & Observability

### 11.1 Health Check (public)

```
GET http://localhost:8081/actuator/health
```

**Expected:** `200 OK` — `{"status": "UP"}`

---

### 11.2 Prometheus Metrics (admin only after H-3 fix)

```
GET http://localhost:8081/actuator/prometheus
Authorization: Bearer <admin_access_token>
```

**Expected:** `200 OK` — Prometheus text format

**Search for custom metrics:**
- `auth_login_success_total`
- `auth_login_failure_total`
- `auth_register_success_total`
- `auth_jwt_invalid_total`
- `auth_jwt_user_inactive_total`
- `auth_token_refresh_success_total`

---

### 11.3 Prometheus Without Auth (should FAIL after H-3 fix)

```
GET http://localhost:8081/actuator/prometheus
```

**Expected:** `403 Forbidden` — only `/actuator/health/**` is public

---

## 12. gRPC — ValidateToken

### 12.1 Test with grpcurl

Install if needed: `brew install grpcurl`

```bash
grpcurl -plaintext \
  -d '{"access_token": "<valid_access_token>"}' \
  localhost:9090 sg.edu.nus.iss.identity.grpc.proto.IdentityService/ValidateToken
```

**Expected:**
```json
{
  "valid": true,
  "userId": "<uuid>",
  "role": "assessor"
}
```

---

### 12.2 Invalid Token

```bash
grpcurl -plaintext \
  -d '{"access_token": "invalid.token.here"}' \
  localhost:9090 sg.edu.nus.iss.identity.grpc.proto.IdentityService/ValidateToken
```

**Expected:**
```json
{
  "valid": false
}
```

---

### 12.3 List gRPC Services

```bash
grpcurl -plaintext localhost:9090 list
```

**Expected:** Shows `sg.edu.nus.iss.identity.grpc.proto.IdentityService`

---

## Test Execution Order

Run in this order for clean state:

1. **Register** (1.1 → 1.5) — creates test user
2. **Login** (2.1 → 2.3) — gets tokens
3. **JWKS** (6.1 → 6.3) — verify public key + auth enforcement
4. **User ops** (5.1 → 5.5) — profile management
5. **Token refresh** (3.1 → 3.2) — token lifecycle
6. **Redis** (8.1 → 8.4) — cache verification
7. **Logout** (4.1) — session cleanup
8. **Rate limiting** (7.1) — brute-force protection
9. **Admin ops** (9.1 → 9.5) — deactivate/activate with Redis verification
10. **Roster** (10.1 → 10.4) — candidate management
11. **Actuator** (11.1 → 11.3) — observability
12. **gRPC** (12.1 → 12.3) — internal API

---

## Cleanup

After testing:
```sql
DELETE FROM sessions;
DELETE FROM candidate_roster;
DELETE FROM users;
```

```bash
redis-cli FLUSHDB
```
