# Arogion Healthcare Platform - Agent Knowledge Base

## 1. Project Vision & Scope
Arogion is a highly scalable healthcare platform (similar to Practo) that supports doctor discovery, appointment booking, teleconsultation, prescriptions, billing, notifications, reviews, and analytics. It serves three primary interfaces:
*   **Patient Application:** Discovery, booking, and healthcare management.
*   **Doctor Application:** Consultations, scheduling, and patient management.
*   **Hospital Management Application:** Operations, staff, billing, and diagnostics.

## 2. Technology Stack Overview
The platform utilizes a modern, cloud-native technology stack to ensure scalability, security, and maintainability.

*   **Frontend (Unified Strategy):** React + TypeScript + Tailwind CSS. A single shared component architecture powers both web and mobile experiences, eliminating the need for a separate mobile codebase.
*   **Backend & Microservices:** Java + Spring Boot + Spring Cloud (API Gateway, routing, load balancing).
*   **Data Storage:** PostgreSQL (relational clinical data) + Flyway (schema migrations).
*   **Caching & State:** Redis (in-memory caching for OTPs, sessions, doctor availability).
*   **Event Streaming:** Apache Kafka (asynchronous domain events and messaging).
*   **Infrastructure & DevOps:** Docker, Kubernetes, AWS, GitHub Actions (CI/CD).
*   **Observability:** Prometheus, Grafana, ELK Stack, OpenTelemetry.

## 3. Microservices Architecture
The backend is strictly domain-driven. Each service owns its database schema and communicates via REST APIs (synchronous) or Kafka (asynchronous). **Cross-service SQL joins are strictly prohibited.**

### Core Services (MVP)
1.  **API Gateway:** Single entry point, routing, rate limiting, and correlation IDs. No business logic.
2.  **Identity/Auth Service:** User registration, JWT/OAuth2 tokens, roles (PATIENT, DOCTOR, HOSPITAL_ADMIN, etc.).
3.  **Patient Service:** Demographics, emergency contacts, consents.
4.  **Doctor Service:** Profiles, specializations, qualifications, affiliations.
5.  **Hospital Service:** Branches, departments, staff, resources.
6.  **Appointment Service:** Booking lifecycle, conflict prevention (double booking), outbox event generation.
7.  **Medical Record Service:** Encounters, diagnoses, clinical history.
8.  **Notification Service:** Email/SMS/Push delivery triggered via Kafka.

### Future/Phase 2+ Services
*   Schedule/Availability, Prescription, Billing/Payment, Laboratory, Pharmacy, Document Management, Review/Rating.
*   **Future Tech Integrations:** AI Symptom Checker, Wearable IoT, Predictive No-Show Analytics, AR Clinic Wayfinding.

## 4. Communication & Data Patterns
*   **Synchronous (REST):** Used for immediate data retrieval (e.g., Gateway routing requests, fetching a doctor's profile).
*   **Asynchronous (Kafka):** Used to decouple workflows. 
    *   *Example Workflow:* `Appointment Service` books a slot -> persists to DB -> publishes `AppointmentBooked` event -> `Notification Service` consumes event and emails the patient.
*   **File Storage:** Binary files (prescriptions, scans) are stored in secure object storage, with metadata managed by the Document Service.

## 5. Development Environment Setup
The local environment is optimized for independent frontend and backend development.

*   **Frontend (VS Code):** Scaffolding via Vite, structured with ESLint, Prettier, and Tailwind IntelliSense.
*   **Backend (IntelliJ IDEA / STS):** Spring Boot projects initialized with Gradle, utilizing Lombok, Spring Data JPA, and Kafka dependencies. 
*   **Local Infrastructure (Docker Compose):** A shared `docker-compose.yml` spins up PostgreSQL, Redis, and Kafka locally for immediate backend testing.
*   **Remote Database Access (Tailscale):** For shared development databases, the team utilizes a private Tailscale network. Teammates connect to a host PC's PostgreSQL instance via `pgAdmin` using secure `/32` subnet rules in `pg_hba.conf`, entirely bypassing public internet exposure.

## 6. Core Architectural Rules for Agents/Developers
1.  **Strict Data Isolation:** Services must never share a database or query another service's tables directly.
2.  **Stateless APIs:** Ensure all domain services are stateless to allow horizontal scaling in Kubernetes.
3.  **Security:** Implement role-based access control (RBAC). Passwords are never stored in plaintext (Argon2id/Bcrypt). APIs must validate user authorization (e.g., doctors can only see their own patients).
4.  **Resiliency:** Use outbox patterns for reliable event publishing. Prevent double-booking via DB constraints and idempotency keys, not just application-level checks.