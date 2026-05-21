# vault-backend

REST API สำหรับ Vault Management System

## Stack

- Java 25 + Spring Boot 3.4.x
- Spring Data JPA (Hibernate 6) + PostgreSQL 15/16
- Spring Security
- AWS SDK v2 (S3 + IoT Core)
- SpringDoc OpenAPI
- **Gradle 9.5** (Kotlin DSL)

## Prerequisites

- Java 25
- PostgreSQL 15/16 (schema จากโปรเจคเดิม)

## Getting Started

**1. Copy environment config**

```bash
cp .env.example .env
```

**2. แก้ไข `.env`**

```env
DATABASE_URL=jdbc:postgresql://localhost:5432/vault_db
DATABASE_USERNAME=postgres
DATABASE_PASSWORD=your_password
ADMIN_USERNAME=admin
ADMIN_PASSWORD=your_password
AWS_REGION=ap-southeast-1
AWS_S3_BUCKET=vault-storage
AWS_IOT_ENDPOINT=
```

**3. Run**

```bash
./gradlew bootRun
```

Server จะ start ที่ `http://localhost:8080`

## Endpoints

| Method | Path | Description |
|---|---|---|
| GET | `/hello` | Health check |
| GET | `/swagger-ui.html` | API documentation |
| GET | `/api/v1/bookings` | List bookings |
| GET | `/api/v1/bookings/{id}` | Get booking |
| POST | `/api/v1/agents/{agentId}/vaults/{vaultId}/bookings` | Create booking |
| DELETE | `/api/v1/bookings/{id}` | Cancel booking |

## Project Structure

```
src/main/java/com/vault/
├── VaultApplication.java
├── config/          SecurityConfig
├── controller/      REST endpoints
├── service/         Business logic
├── repository/      Spring Data JPA
├── entity/          JPA entities (DB tables)
├── dto/             Request / Response records
└── exception/       GlobalExceptionHandler
```

## Database

ใช้ schema เดิมจาก PostgreSQL — ไม่ต้อง migrate

```yaml
# application.yml
jpa:
  hibernate:
    ddl-auto: none      # dev — ไม่แตะ schema
    # ddl-auto: validate  # เปลี่ยนเมื่อต่อ DB จริงเพื่อตรวจสอบ
```

## Build

```bash
./gradlew build
java -jar build/libs/vault-backend-0.0.1-SNAPSHOT.jar
```
