# Docker and Bruno API Testing Guide

This guide explains how to start the Arogion services with Docker and test the authentication API with Bruno.

## Architecture

The current local setup runs two Arogion services in Docker:

```text
Bruno
  |
  | http://localhost:8080
  v
API Gateway container
  |
  | forwards /api/v1/auth/** to the auth service
  v
Auth Service container
  |
  | connects using the configured JDBC URL
  v
External PostgreSQL database
```

The services are defined in [`docker-compose.yml`](./docker-compose.yml).

| Service | Container port | Windows host port | Purpose |
|---|---:|---:|---|
| API Gateway | 8080 | 8080 | Main entry point for API requests |
| Auth Service | 8080 | 8081 | Direct auth-service testing and gateway target |

For normal Bruno testing, use the API Gateway on port `8080`.

## Prerequisites

Install and start:

- Docker Desktop for Windows
- Bruno
- Access to the PostgreSQL database configured by the auth service

The auth service uses the database URL from [`services/auth-service/src/main/resources/application.yaml`](./services/auth-service/src/main/resources/application.yaml). Docker does not start PostgreSQL in the current configuration.

## Start the application

Open PowerShell and move to the repository root:

```powershell
cd C:\Arogion
```

Make sure Docker Desktop is running:

```powershell
docker version
```

### First run or after code/configuration changes

Use `--build` after changing Java source, YAML files, Gradle files, or Dockerfiles:

```powershell
docker compose up -d --build
```

### Normal subsequent run

If no source or configuration files changed:

```powershell
docker compose up -d
```

## Verify that the services are running

Run:

```powershell
docker compose ps
```

Both services should show an `Up` status:

```text
arogion-api-gateway
arogion-auth-service
```

View recent logs:

```powershell
docker compose logs --tail 100 api-gateway
docker compose logs --tail 100 auth-service
```

Follow logs while testing:

```powershell
docker compose logs -f api-gateway auth-service
```

Press `Ctrl+C` to stop viewing logs. This does not stop the containers.

## Bruno request settings

Use this base URL:

```text
http://localhost:8080
```

Every request should include:

```text
Content-Type: application/json
```

You can create a Bruno environment with:

```text
baseUrl = http://localhost:8080
email = bruno-test-001@example.com
password = Password123!
transactionId = 
accessToken = 
```

## Authentication test sequence

Run the requests in this order.

### 1. Request a signup OTP

```http
POST http://localhost:8080/api/v1/auth/signup/email/request-otp
Content-Type: application/json
```

Body:

```json
{
  "email": "bruno-test-001@example.com"
}
```

Expected response:

```json
{
  "transactionId": "TRANSACTION_ID"
}
```

Copy the returned `transactionId` for the next two requests.

### 2. Verify the OTP

```http
POST http://localhost:8080/api/v1/auth/signup/email/verify-otp
Content-Type: application/json
```

Body:

```json
{
  "transactionId": "TRANSACTION_ID",
  "otp": "123456"
}
```

### 3. Complete signup

```http
POST http://localhost:8080/api/v1/auth/signup/email/complete
Content-Type: application/json
```

Body:

```json
{
  "transactionId": "TRANSACTION_ID",
  "password": "Password123!"
}
```

Expected response shape:

```json
{
  "accessToken": "JWT_TOKEN",
  "tokenType": "Bearer",
  "expiresInSeconds": 3600,
  "userId": "USER_ID",
  "email": "EMAIL_RETURNED_BY_SERVICE"
}
```

Save the returned `accessToken` and `email`.

### 4. Sign in

Use the email returned by the complete-signup response:

```http
POST http://localhost:8080/api/v1/auth/signin/email
Content-Type: application/json
```

Body:

```json
{
  "email": "EMAIL_RETURNED_BY_SERVICE",
  "password": "Password123!"
}
```

Expected response:

```json
{
  "accessToken": "JWT_TOKEN",
  "tokenType": "Bearer",
  "expiresInSeconds": 3600,
  "userId": "USER_ID",
  "email": "EMAIL_RETURNED_BY_SERVICE"
}
```

For a protected request, add:

```text
Authorization: Bearer JWT_TOKEN
```

## Direct auth-service testing

To bypass the gateway and test the auth container directly, use port `8081`:

```text
http://localhost:8081/api/v1/auth/signup/email/request-otp
http://localhost:8081/api/v1/auth/signup/email/verify-otp
http://localhost:8081/api/v1/auth/signup/email/complete
http://localhost:8081/api/v1/auth/signin/email
```

Use direct testing only when diagnosing gateway routing. The normal application path is:

```text
Bruno → http://localhost:8080 → API Gateway → Auth Service
```

## Stop the application

When testing is complete:

```powershell
cd C:\Arogion
docker compose down
```

This stops and removes the Compose containers but does not remove the images.

Other useful commands:

```powershell
# Restart without rebuilding
docker compose restart

# Stop containers without removing them
docker compose stop

# Remove containers and images created by Compose
docker compose down --rmi local
```

## Troubleshooting

### Bruno returns `404 Not Found`

Confirm that the gateway is running:

```powershell
docker compose ps
```

Rebuild the gateway after configuration or Java route changes:

```powershell
docker compose up -d --build api-gateway
```

Check gateway logs:

```powershell
docker compose logs --tail 150 api-gateway
```

Use the gateway URL, not a missing or different port:

```text
http://localhost:8080/api/v1/auth/...
```

### Bruno returns `500 Internal Server Error`

Check both services:

```powershell
docker compose logs --tail 150 api-gateway auth-service
```

Test the auth service directly:

```text
http://localhost:8081/api/v1/auth/signup/email/request-otp
```

If direct auth requests work but gateway requests fail, the problem is in gateway routing or container connectivity.

### A container is not running

Run:

```powershell
docker compose ps
docker compose logs --tail 150 api-gateway
docker compose logs --tail 150 auth-service
```

Then rebuild:

```powershell
docker compose up -d --build
```

### Database connection failure

The auth service connects to the PostgreSQL host configured in its application YAML. Confirm that:

- The database host is reachable from Docker.
- PostgreSQL is listening on port `5432`.
- The database name exists.
- The configured username and password are valid.
- Firewall rules allow the connection.

## Current authentication limitations

The current baseline authentication implementation has two known limitations:

1. OTP verification is a placeholder. It accepts a non-empty OTP but does not yet validate a stored challenge or expiry.
2. Signup completion currently generates an email internally instead of recovering the email associated with the transaction. Use the `email` returned by the complete-signup response for the sign-in test.

These behaviors are implemented in [`AuthService.java`](./services/auth-service/src/main/java/com/arogion/auth/AuthService.java) and should be replaced before production use.

## Quick daily workflow

```powershell
cd C:\Arogion
docker compose up -d
docker compose ps
```

Then test through Bruno using:

```text
http://localhost:8080/api/v1/auth/...
```

When finished:

```powershell
docker compose down
```
