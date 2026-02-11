# Nixathon 2026

## Requirements

- Java 25
- Maven 3.9+ (or use the included `./mvnw`)

## Run locally

```bash
./mvnw spring-boot:run
```

The app starts on `http://localhost:8080` by default.

## Build

```bash
./mvnw clean package
```

## Deploy to VPS (Remote Docker Context over SSH)

This is the direct workflow: your local machine triggers deploys on the VPS Docker daemon over SSH.

### Prerequisites

- Local machine:
  - Docker with Compose support
  - SSH key access to your VPS user
- VPS:
  - Docker Engine + Docker Compose plugin installed
  - Port `8080` open (or a reverse proxy configured)

### One-time setup

Create a Docker context that points to the VPS daemon through SSH:

```bash
docker context create nixathon-vps --docker "host=ssh://<user>@<vps-public-ip>"
docker --context nixathon-vps info
```

### Deploy

From the project root:

```bash
docker --context nixathon-vps compose up -d --build
docker --context nixathon-vps compose ps
```

Quick health check:

```bash
curl http://<vps-public-ip>:8080/healthz
```

### Convenience script

You can also deploy with:

```bash
./scripts/deploy-vps.sh
```


### Development workflow

1. Make code changes locally.
2. Run deploy command:
   - `docker --context nixathon-vps compose up -d --build`
   - or `./scripts/deploy-vps.sh`
3. Verify:
   - `docker --context nixathon-vps compose ps`
   - `curl http://<vps-public-ip>:8080/healthz`

## API

### Health check

- Method: `GET`
- Path: `/healthz`
- Response:

```json
{
  "status": "OK"
}
```

### Pick move

- Method: `POST`
- Path: `/api/game/move`
- Purpose: receives game state and returns the move selected by your strategy
- Current request body model: `MoveRequest` (placeholder, currently empty object: `{}`)
- Current response body model: `MoveResponse` (placeholder, currently empty object: `{}`)

Example:

```bash
curl -X POST http://localhost:8080/api/game/move \
  -H "Content-Type: application/json" \
  -d '{}'
```

## Error format

Validation and runtime errors are returned in a consistent JSON format:

```json
{
  "timestamp": "2026-02-09T12:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid request body",
  "path": "/api/game/move"
}
```
