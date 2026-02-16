# 오픈삼국 (OpenSamguk)

Web-based Three Kingdoms strategy game built with modern technologies.

## Tech Stack

| Layer    | Technology             |
| -------- | ---------------------- |
| Backend  | Spring Boot 3 (Kotlin) |
| Frontend | Next.js 15             |
| Database | PostgreSQL 16          |
| Cache    | Redis 7                |

## Getting Started

### 1. Start Database Services

```bash
docker-compose up -d
```

This starts PostgreSQL (port 5432) and Redis (port 6379).

### 2. Run Backend

```bash
cd backend
./gradlew bootRun
```

### 3. Run Frontend

```bash
cd frontend
pnpm install
pnpm dev
```

## Project Structure

```
opensam/
├── backend/          # Spring Boot 3 (Kotlin) backend
├── frontend/         # Next.js 15 frontend
├── docker-compose.yml
├── CLAUDE.md
└── README.md
```
