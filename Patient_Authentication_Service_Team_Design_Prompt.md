# Shared Authentication Service

**Architecture, Database Design & Implementation Prompt**

For Patients • Doctors • Hospitals

Technology baseline: Java Spring Boot · PostgreSQL · React · Docker


## 1. Purpose and scope

Build one centrally managed Authentication Service that can serve three separate applications: (1) Patient Application, (2) Doctor Application, and (3) Hospital Application. Development starts with the Patient Application, but the database and API contracts must be designed now so the Doctor and Hospital applications can be added without redesigning authentication or creating separate user stores.

The service owns identity, credentials, verification, sessions/tokens, application access, role assignments, and authentication audit events. It must not become the storage location for patient clinical records, doctor clinical/workforce data, or hospital operational data.


## 2. Core architecture principles

- One shared identity per person where appropriate; do not create a new auth user merely because the same person uses another application.
- Separate authentication from authorization: authentication establishes who the user is; authorization checks which application, organization, and actions they may access.
- Treat application access, platform roles, and hospital-specific roles as different concepts.
- Hospital Admin and Hospital Staff are organization-scoped roles, not global roles. A user may be staff at one hospital and admin at another only if explicitly granted.
- Use a stable internal UUID as the user ID. Do not use email or phone number as a primary key.
- Design tenant/organization isolation into APIs and database relationships from the beginning.
- Start with patient signup and sign-in, but keep schema and API extensible for doctor and hospital onboarding.
- Keep medical/clinical data in separate domain services and databases/schemas with their own access policies.

## 3. Applications and authorization model


| Application | Initial access model | Notes |

| --- | --- | --- |

| Patient Application | Patient app access; patient profile linked to auth user | Implement first. Patient-specific profile belongs to patient domain. |

| Doctor Application | Doctor app access; doctor profile and approved credentials/status | Future-ready; do not assume every account is a doctor. |

| Hospital Application | Hospital membership with Hospital Admin or Hospital Staff role | Roles must be scoped to a specific hospital/tenant. |




### Role design rules

- Do not add one broad `role` column to `auth_users` to represent every role across all applications.
- Use application access assignments for access to PATIENT, DOCTOR, or HOSPITAL applications.
- Use organization membership and role-assignment tables for Hospital Admin and Hospital Staff.
- A hospital role must always be evaluated with the hospital/organization ID. Never authorize a hospital operation from the role name alone.
- Define a least-privilege permission matrix for Hospital Admin and Hospital Staff before exposing hospital APIs.
- Do not grant hospital roles through public signup. Hospital membership should be created through an invitation, admin workflow, or controlled provisioning process.
- Future patient and doctor role labels should be added through the same authorization model, not hard-coded into authentication logic.

## 4. Required signup and sign-in flows


### A. Email and password signup

1. User submits email. Normalize it consistently and initiate an email OTP challenge.
1. Validate OTP server-side; enforce expiry, maximum attempts, resend cooldown, and single use.
1. After email verification, require phone number entry and phone OTP verification. Both verifications are mandatory for email/password signup.
1. For the current development/test environment only, use fixed phone OTP `1234` through a development-only implementation. Do not use this as a production security mechanism.
1. Only after email and phone verification, accept password and confirmation; validate on the server and hash with Argon2id or an approved modern password-hashing algorithm.
1. In a database transaction, create auth user, password credential, verified phone association, initial PATIENT application access, and patient profile if the patient domain requires it.
1. Set the account to active only when all required steps have succeeded.
1. After commit, enqueue a registration-success email through a transactional outbox/retry process. Email delivery failure must not create duplicate accounts or falsely roll back an already committed account.

### B. Google signup

1. Authenticate through Google OpenID Connect. Validate signature, issuer, audience, expiry, and applicable state/nonce protections on the backend.
1. Use Google's stable `sub` claim as the external identity key. Never identify a Google account by email alone.
1. If Google's verified-email claim is valid and true, treat the email as verified. If absent or false, require separate email verification before activation.
1. Require phone OTP verification before creating an active account. Fixed OTP `1234` is allowed only in development/test mode.
1. Create the user, Google external identity, verified phone association, initial PATIENT application access, and patient profile if required in one transaction.
1. Do not require a password for Google-only accounts. Send registration-success email only for newly created accounts.
1. Do not silently link a Google identity to an existing account merely because email addresses match; require secure reauthentication/account linking.

### C. Mobile-only signup

1. Accept a phone number, normalize it to E.164 where possible, and initiate a phone OTP challenge.
1. Verify phone OTP. In development/test mode only, accept `1234` for a valid, unexpired challenge with attempt limits and single-use consumption.
1. After successful verification, create the auth user, verified phone association, initial PATIENT application access, and patient profile if required.
1. Do not require email or password. A registration email can only be sent if a verified email is later associated with the account.
1. Mobile sign-in uses a fresh OTP challenge and must not reuse the signup challenge.

### D. Sign-in and account lifecycle

- Support Google sign-in, email/password sign-in, and phone OTP sign-in.
- Do not issue a fully authenticated session for a suspended/disabled account or an account missing required verification.
- Mobile-only users must not be forced to create an email or password.
- Support logout, refresh-token rotation, token/session revocation, password reset/change, and account recovery.
- When adding a doctor or hospital workflow, reuse the same identity/session service while applying app- and organization-specific authorization.

## 5. Database design requirements

Use PostgreSQL, UUID primary keys, UTC timestamps, Flyway migrations, foreign keys, unique constraints, check constraints, and indexes. The implementation team should produce an ER diagram and review it before writing controllers or UI flows.


| Table | Responsibilities and constraints |

| --- | --- |

| auth_users | Core identity, nullable normalized email, email_verified_at, account status, timestamps. Partial unique index on normalized email when not null. |

| auth_credentials | Optional password credential; unique user_id; store password hash only. |

| auth_external_identities | Provider and provider_subject; unique(provider, provider_subject); store provider email only as metadata. |

| auth_user_phones | Phone association, normalized phone, verified_at, primary flag; enforce the agreed uniqueness policy for verified numbers. |

| auth_signup_transactions | Persist incomplete signup across requests/restarts; method, status, email/phone verification timestamps, expiry and state. |

| auth_verification_challenges | Email/phone channel, purpose, challenge hash/protected test representation, expiry, attempt_count, consumed_at, transaction binding. |

| auth_sessions | Hashed refresh token, expiry, revocation, last-used timestamp; never store raw refresh tokens. |

| auth_application_access | User-to-application access assignments such as PATIENT, DOCTOR, HOSPITAL; unique user/application pair or documented multi-membership design. |

| organizations | Hospital/tenant identity and status; keep organization metadata separate from auth user. |

| organization_memberships | User-to-hospital membership, membership status, invited/accepted timestamps; unique user/organization pair where applicable. |

| organization_role_assignments | Hospital Admin and Hospital Staff assignments scoped to a membership/organization; define role keys and constraints. |

| auth_audit_events | Security events and timestamps; never record passwords, OTPs, raw tokens, or unnecessary sensitive data. |

| email_outbox | Registration and security email events, delivery status, retry schedule and idempotency. |

| patient_profiles | Patient-domain profile linked to auth user; created for patient onboarding, not for doctor/hospital data. |




### Required database constraints and indexes

- Partial unique index for normalized_email where it is not null.
- Unique constraint on external identity `(provider, provider_subject)`.
- Unique constraint on auth_credentials.user_id.
- Partial unique index for verified normalized phone number if the business policy permits one account per verified phone.
- Indexes for active verification challenges, expiry cleanup, unrevoked sessions, pending outbox events, audit events by user/time, and organization membership lookup.
- Foreign keys and explicit ON DELETE policies for each relationship.
- Transactions and uniqueness constraints must protect against concurrent duplicate signup requests.
- Store verified status server-side; never trust verification booleans sent by the browser.

## 6. Authorization and hospital roles

Authentication is shared, but authorization must be context-aware. Every request to a hospital resource must resolve the user's active membership in the target hospital and the permissions associated with that membership.


| Role | Typical permission scope | Restrictions |

| --- | --- | --- |

| Hospital Admin | Manage approved hospital settings, invite/manage staff, assign allowed hospital roles, access permitted hospital operations. | Only within assigned hospital; cannot grant platform-wide privileges or bypass clinical permissions. |

| Hospital Staff | Perform explicitly permitted operational tasks within the assigned hospital. | No staff management or admin-only actions unless separately granted. |



These are baseline examples, not final clinical permissions. The team must approve a permission matrix with the hospital product owner. Enforce authorization on the backend for every protected operation; hiding a React button is not access control.


## 7. Suggested API groups

Email signup: POST /api/v1/auth/signup/email/request-otp; POST /api/v1/auth/signup/email/verify-otp; POST /api/v1/auth/signup/email/request-phone-otp; POST /api/v1/auth/signup/email/verify-phone-otp; POST /api/v1/auth/signup/email/complete

Google: POST /api/v1/auth/google or a documented OIDC redirect/callback flow; endpoint(s) to finish phone verification and signup.

Mobile: POST /api/v1/auth/signup/mobile/request-otp; POST /api/v1/auth/signup/mobile/verify-otp; POST /api/v1/auth/signin/mobile/request-otp; POST /api/v1/auth/signin/mobile/verify-otp

Sign-in/session: POST /api/v1/auth/signin/email; POST /api/v1/auth/token/refresh; POST /api/v1/auth/signout; GET /api/v1/auth/me

Account security: POST /api/v1/auth/password/forgot; POST /api/v1/auth/password/reset; POST /api/v1/auth/password/change; secure account-linking endpoints.

Application access: Authenticated endpoint(s) to list the user's authorized applications and active organization memberships; do not let the client self-assign roles.


## 8. Fixed test OTP policy

- Use a PhoneOtpProvider interface so the fixed test implementation can be replaced by an SMS provider without changing signup business logic.
- Enable fixed OTP only through explicit development/test configuration, never through request parameters or React state.
- Production configuration must fail fast if fixed OTP mode is enabled.
- Test OTP still requires a valid challenge, correct purpose and destination, expiry, attempt limits, and one-time consumption.
- Never log or return the fixed OTP from production endpoints. Use the development profile or test fixtures to expose test behavior.
- Email OTPs should be securely generated and delivered via an email provider; do not reuse the fixed phone OTP for email verification.

## 9. Security and operational requirements

- Use HTTPS in deployed environments, strict CORS allowlists, request validation, and safe error responses.
- Use Argon2id or an approved modern password hashing algorithm; never store plaintext passwords.
- Protect OAuth state/nonce and validate Google tokens on the backend.
- Use short-lived access tokens and rotated, revocable refresh tokens or an explicitly selected secure server-side session design.
- Apply rate limits and abuse protection to signup, OTP request/verification, login, and password recovery.
- Use environment variables/secrets management; commit only `.env.example`, never live secrets.
- Use least-privilege database users and separate migration credentials where practical.
- Do not log OTPs, passwords, bearer tokens, refresh tokens, or unnecessary patient data.
- Use audit logging and tenant-scoped authorization checks. Avoid exposing whether an email/phone exists through unauthenticated error messages.
- Define retention and deletion rules for expired OTP challenges, signup transactions, sessions, and audit data.

## 10. Expected team deliverables

1. Architecture diagram and ER diagram reviewed by the team.
1. PostgreSQL DDL/Flyway migrations with all constraints and indexes.
1. Data dictionary and a short rationale for every table.
1. Sequence diagrams for email signup, Google signup, mobile-only signup, all sign-in methods, and hospital role authorization.
1. OpenAPI/Swagger specification with request/response examples and error codes.
1. Spring Boot implementation organized into controllers, services, repositories, DTOs, entities, security, providers, and configuration.
1. React integration contract and sample client flows.
1. Docker Compose setup for API and PostgreSQL, health checks, persistent database volume, and environment-based configuration.
1. Automated unit/integration tests, including concurrent signup and authorization isolation tests.
1. README with prerequisites, local run instructions, migrations, test OTP rules, environment variables, and troubleshooting.
1. A phased roadmap showing patient-first delivery and later doctor/hospital onboarding without schema redesign.

## 11. Required test scenarios

- Email signup succeeds only after email OTP, phone OTP, and password confirmation.
- Google signup requires a valid Google identity and phone verification; unverified Google email requires email OTP.
- Mobile-only signup succeeds with phone verification and no email/password.
- Phone OTP `1234` works only under development/test profile; production startup fails if fixed mode is enabled.
- Wrong, expired, consumed, and excessive-attempt OTPs are rejected.
- Duplicate email, phone, or Google identity cannot create duplicate accounts, including concurrent requests.
- A mobile-only user can sign in with phone OTP without being forced to create an email/password.
- A suspended or disabled user cannot obtain a fully authenticated session.
- A hospital staff member cannot perform admin-only actions.
- A hospital admin in Hospital A cannot access Hospital B without a valid membership there.
- A user cannot grant themselves application access, organization membership, or hospital roles by changing a request.
- Email delivery retries do not create duplicate accounts or duplicate confirmation events.
- Refresh token rotation, logout, revocation, password reset, and account-linking protections work correctly.

## 12. Implementation phases

Phase 1 — Foundation: Agree the schema, ERD, app/role model, migration strategy, API contract, and Docker development environment.

Phase 2 — Patient authentication: Implement email signup with email then phone verification, mobile-only signup, password login, sessions, and tests.

Phase 3 — Google: Implement Google OIDC, verified-email handling, phone verification, safe account linking, and tests.

Phase 4 — Authorization foundation: Implement application access, organizations, memberships, Hospital Admin/Staff role assignments, and tenant-isolation tests.

Phase 5 — Doctor and hospital apps: Connect each application through the shared auth APIs and add domain-specific profile/onboarding workflows.

Phase 6 — Production readiness: Integrate email and SMS providers, secret management, monitoring, rate limiting, security review, backups, and deployment controls.


## 13. Master prompt for the coding assistant


| Act as a senior backend architect and security engineer. Design and implement a shared Authentication Service for three applications: Patient, Doctor, and Hospital. Use Java Spring Boot, PostgreSQL, React, Flyway, and Docker Compose. We are implementing the Patient Application first, but the schema and API must support the Doctor and Hospital applications later without creating separate identity stores. The Hospital Application has two organization-scoped roles: HOSPITAL_ADMIN and HOSPITAL_STAFF. Do not model these as one global role column on the user. Separate identity, application access, organization membership, and role assignments. Enforce hospital/tenant isolation on every protected backend operation. Never let public signup self-assign a hospital role; use invitations or controlled provisioning. Signup methods: 1. Email/password: email OTP verification first, then mandatory phone OTP verification, then password + confirmation, then transactional account creation and registration-success email. 2. Google: validate Google OpenID Connect identity and verified-email claim on the backend, then require phone OTP before activation. If Google email is not verified, require email OTP. Do not require a password for Google-only accounts. 3. Mobile-only: phone OTP is sufficient; do not require email or password. There is currently no SMS provider. Implement a replaceable PhoneOtpProvider interface and a fixed phone test OTP `1234` only for development/automated tests. Production startup must fail if fixed OTP mode is enabled. Test mode must still enforce challenge purpose, destination, expiry, attempt limits, and single-use consumption. Never use fixed OTP for email verification. Database requirements: normalized identities, UUID primary keys, unique email when present, unique provider+subject, phone verification associations, persistent signup transactions, verification challenges, optional password credentials, sessions/refresh tokens, application access, organizations, organization memberships, role assignments, audit events, email outbox, and patient profiles. Use PostgreSQL constraints, indexes, foreign keys, transactions, and Flyway migrations. Keep clinical data outside the auth service. Security requirements: modern password hashing, backend token validation, OAuth state/nonce protection, short-lived access tokens, rotated/revocable refresh tokens or a documented secure session alternative, rate limits, safe errors, least-privilege DB roles, secrets via environment/configuration, audit logs without secrets, and account-linking protection. Work in this order: 1. Present architecture, ER diagram, table dictionary, constraints/indexes, and authorization model. 2. Explain signup state transitions and race-condition handling. 3. Define REST endpoints and OpenAPI request/response/error contracts. 4. Implement the PostgreSQL migrations and tests. 5. Implement Spring Boot services, providers, security, and controllers. 6. Provide React integration examples and Docker Compose. 7. Provide README and environment templates. 8. Run build and integration tests; do not claim code compiles unless the build was actually executed successfully. Deliver a patient-first implementation that is extensible to doctor and hospital applications. Explicitly list incomplete items and never label placeholders as production-ready. |

| --- |




## 14. Team decisions to record before coding

- Confirm whether a verified phone number can belong to only one account across all three applications.
- Decide whether a person may have Patient and Doctor access on the same auth user, and what proof/approval is required for Doctor access.
- Define the Hospital Admin/Staff permission matrix and hospital invitation/approval process.
- Choose JWT refresh-token architecture versus server-side sessions, and define token storage for the React client.
- Choose the email delivery provider and future SMS provider; preserve provider interfaces.
- Define account linking, account recovery, data retention, and deactivation policies.
End of team design brief
