# Arogion Auth Service: Google OAuth Implementation Brief

**Audience:** Teammate implementing the existing Spring Boot
authentication microservice\
**Goal:** Add Google sign-in/sign-up to the existing Arogion auth
service and PostgreSQL database, while preserving the current signup and
phone-OTP verification flow.

## 1. Required behavior

The backend must: - Accept and validate a Google **ID token**. -
Identify the external account using Google's stable `sub` claim. -
Create or resume a pending signup for a new identity. - Require phone
OTP verification before activating a new account. - Issue **Arogion
credentials** only according to Arogion's own authentication policy. -
Reuse existing signup, OTP, user, session, and token services wherever
possible.

Google's access token or ID token is **not** an Arogion API token.
Getting a token in Bruno alone does not create a PostgreSQL user.

## 2. Flow

1.  Client obtains Google tokens through the configured OAuth/OIDC flow.
2.  Client sends the Google ID token to Arogion.
3.  Arogion verifies the token server-side.
4.  Arogion looks up an external identity using
    `(provider = GOOGLE, subject = sub)`.
5.  If it is an existing linked, active account, follow the existing
    login policy and issue Arogion credentials.
6.  If it is a new identity, create/resume a pending signup and require
    phone verification.
7.  After OTP verification and other required signup checks, activate
    the account and issue Arogion credentials.

``` text
Client -> Google sign-in -> Google ID token
                         -> POST /api/v1/auth/google
                         -> Verify token
                         -> Find identity by Google `sub`
                            | existing linked active user -> Arogion credentials
                            | new identity -> pending signup -> phone OTP
                                                -> activate -> Arogion credentials
```

## 3. Proposed endpoint

Adapt names to existing route and DTO conventions.

**POST** `/api/v1/auth/google`\
**Content-Type:** `application/json`

Request:

``` json
{
  "idToken": "<GOOGLE_ID_TOKEN>"
}
```

For a new identity, return a pending-signup response, for example:

``` json
{
  "status": "PHONE_VERIFICATION_REQUIRED",
  "signupTransactionId": "<opaque-transaction-id>",
  "message": "Verify your phone number to complete signup."
}
```

For an existing linked, active identity, return the Arogion-issued
access token response using the service's existing response format.

Do not issue normal protected-API access to a new account before the
required phone verification. If a restricted signup token is part of the
existing design, it must authorize only signup/verification actions.

Reuse existing phone-OTP endpoints. Do not create duplicate OTP APIs
unless the current service has none.

## 4. ID token validation --- mandatory

Use a maintained Google/OIDC verification library compatible with the
project's Java and Spring Boot versions. Do not implement JWT signature
verification manually.

Validate at least: - Signature against Google's trusted signing keys. -
`iss` (issuer). - `aud` includes the exact Google OAuth client ID
configured for this environment. - `exp` (expiry) and sensible `iat`
handling. - `sub` exists and is non-empty. - `email_verified` before
treating Google's email as verified. - `nonce` when the initiating OIDC
flow used a nonce.

The component managing the browser authorization-code flow must also
validate OAuth `state` to prevent CSRF. Do not trust a token just
because its JWT payload can be decoded.

Use claims from the **validated** ID token, not client-supplied
name/email values.

## 5. Account-linking rules

-   Use `(provider = GOOGLE, subject = sub)` as the external identity
    key.
-   Never automatically link a Google identity to an existing Arogion
    account merely because the email matches.
-   If email matches an existing account but the Google identity is
    unlinked, require an explicit account-linking flow that proves
    control of the existing account, or return a safe conflict/next
    step.
-   If `email_verified` is false, do not mark Arogion email as verified
    based on Google.
-   A Google-only account does not need a local password unless the
    product explicitly supports adding one later.
-   Never log ID tokens, access/refresh tokens, OTPs, or client secrets.

## 6. PostgreSQL persistence

Extend the **existing auth database/schema**. Do not create a separate
database solely for Google OAuth. Check the current schema before adding
tables; reuse existing equivalents.

Relevant logical entities from the project design:

  -----------------------------------------------------------------------
  Table / entity                      Responsibility
  ----------------------------------- -----------------------------------
  `auth_users`                        Canonical Arogion account and
                                      lifecycle status

  `auth_external_identities`          Provider and stable Google `sub`,
                                      linked to the Arogion user

  `auth_signup_transactions`          Pending signup state and expiry

  `auth_verification_challenges`      OTP challenge, expiry, attempts and
                                      verification state

  `auth_user_phones`                  Phone number and verification
                                      status

  `auth_credentials`                  Local password credential only if a
                                      password exists

  `auth_sessions`                     Arogion session/refresh-token
                                      state, if used by current design

  `auth_audit_events`                 Security audit events without
                                      secret/token values
  -----------------------------------------------------------------------

The schema may also include `patient_profiles` and application-access
records. Create them only as required by the existing domain workflow.

Integrity requirements: - Unique constraint/index on
`(provider, provider_subject)` in external identities. - Foreign keys to
the owning Arogion user and signup records. - Transactional writes for
related records. - Handle concurrent requests by relying on the unique
constraint and re-reading an identity after a uniqueness conflict. -
Keep pending and active statuses distinct. - Store OTPs securely (prefer
hashes), with expiry, attempt limits, and one-time use. - Use Flyway
migrations if that is the project's migration tool.

The development/test OTP `1234`, if already part of the project, must be
enabled only in development/test---not production.

## 7. Suggested code responsibilities

Adapt to the existing package layout: - Controller: validate request
shape and map service results to HTTP responses. - Google token
verifier: validate Google ID tokens using a trusted library. - Google
auth service: orchestrate existing-account login or pending signup. -
Existing signup/OTP/session/token services: reuse these; avoid parallel
implementations. - Repositories: query external identity by provider and
subject. - Flyway migration: add only genuinely missing schema
constraints/columns/tables.

Keep secrets in environment variables or the deployment secret manager.
Example:

``` properties
arogion.oauth.google.client-id=${GOOGLE_CLIENT_ID}
```

A client secret is needed for a backend performing the
authorization-code exchange; it does not replace ID-token validation.
Never commit client secrets. A previously exposed Google client secret
should be rotated before real use.

## 8. Error handling

Use the service's existing error-response format. Expected behavior: -
Missing/malformed token: `400 Bad Request`. - Invalid signature, issuer,
audience, or expired token: `401 Unauthorized`. - Email match but no
linked Google identity: safe conflict/account-linking response; no
silent link. - New user without verified phone: pending signup response,
no normal access. - Wrong/expired OTP or too many attempts: existing OTP
policy. - Google verification dependency unavailable: controlled server
error; never skip verification. - Duplicate/concurrent identity
creation: safely re-read existing identity.

Do not return stack traces, tokens, secrets, or unnecessary claims to
clients.

## 9. Bruno test plan

### A. Test the Google access token independently

-   Method: `GET`
-   URL: `https://openidconnect.googleapis.com/v1/userinfo`
-   Auth: inherit the collection's OAuth 2.0 configuration.
-   Send the request.

A valid token with suitable scopes should return `200 OK` and Google
profile claims. This only tests the Google token; it does not prove the
Arogion backend is implemented. For the backend endpoint below, use the
**ID token**, not the access token.

### B. Call Arogion

-   Method: `POST`
-   URL:
    `http://localhost:<published-auth-service-port>/api/v1/auth/google`
-   Header: `Content-Type: application/json`
-   Body:

``` json
{
  "idToken": "<GOOGLE_ID_TOKEN>"
}
```

Use the actual host port published by Docker/Spring Boot. A Docker
hostname such as `arogion-auth-service` is normally reachable from peer
containers, but not automatically from Bruno running on Windows. From
the host, use `localhost` if the port is published.

Expected outcomes: - New identity: pending signup / phone verification
required. - Existing linked active identity: Arogion authentication
response. - Invalid or expired token: rejected, with no account
activation.

### C. Verify phone OTP and database state

1.  Continue signup using the returned transaction ID and existing OTP
    APIs.
2.  Verify the phone using the current contract.
3.  Confirm activation happens only after successful OTP verification.
4.  Confirm Arogion credentials are returned only at the appropriate
    step.
5.  Inspect PostgreSQL: one external identity for the Google `sub`,
    pending status before OTP, verified phone and active status only
    after successful completion.

### D. Negative tests

-   Invalid signature/token, expired token, and wrong audience are
    rejected.
-   Repeating the request does not create duplicate external identities.
-   Matching email alone does not link accounts.
-   New users cannot access protected endpoints before phone
    verification.
-   Invalid/expired OTP does not activate the account.
-   Logs contain no raw tokens, OTPs, or secrets.

## 10. Acceptance checklist

-   [ ] Endpoint accepts a Google ID token, not arbitrary email or an
    unverified access token.
-   [ ] Signature, issuer, audience and expiry are verified server-side.
-   [ ] Google `sub` is the stable identity key.
-   [ ] New signup requires phone verification before activation.
-   [ ] Email matching never silently links accounts.
-   [ ] Existing signup/OTP/session services are reused where possible.
-   [ ] PostgreSQL migrations and uniqueness constraints prevent
    duplicate identities.
-   [ ] Arogion issues its own credentials according to its
    authentication policy.
-   [ ] Bruno happy-path and negative tests pass.
-   [ ] No tokens, OTPs or secrets are committed or logged.

## Final implementation principle

**Google establishes the external identity. Arogion validates that
identity, applies its own signup and phone-verification rules, persists
the account state, and issues Arogion credentials.** Before coding,
inspect the existing controllers, DTOs, signup transaction service, OTP
endpoints, account statuses, token/session service, repositories and
migrations. Extend those contracts rather than building a parallel auth
system.
