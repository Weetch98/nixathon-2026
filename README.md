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

## Logging

- Every request is logged with start/end entries, status code, and duration.
- Correlation IDs are supported via `X-Request-Id`; if missing, one is generated and returned in the response header.
- Logs include `req=<requestId>` through MDC in the console pattern.
- Default levels are configured in `src/main/resources/application.properties`:
  - `logging.level.root=INFO`
  - `logging.level.me.beratta.nixathon=DEBUG`

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

### Build info

- Method: `GET`
- Path: `/build`
- Response:

```json
{
  "build": "dev-local"
}
```

Build number is read from `build.properties` via key `build.number`.
Default file: `src/main/resources/build.properties`.
Optional override at runtime: `./build.properties` next to the jar/container working directory.

### Negotiation turn

- Method: `POST`
- Path: `/negotiate`
- Purpose: receives negotiation state and returns diplomacy messages.

Example:

```bash
curl -X POST http://localhost:8080/negotiate \
  -H "Content-Type: application/json" \
  -d '{
    "gameId": 1,
    "turn": 1,
    "playerTower": {"playerId": 10, "hp": 100, "armor": 0, "resources": 20, "level": 1},
    "enemyTowers": [{"playerId": 11, "hp": 100, "armor": 0, "level": 1}],
    "combatActions": []
  }'
```

### Combat turn

- Method: `POST`
- Path: `/combat`
- Purpose: receives combat state and returns armor/attack/upgrade actions.

Example:

```bash
curl -X POST http://localhost:8080/combat \
  -H "Content-Type: application/json" \
  -d '{
    "gameId": 1,
    "turn": 1,
    "playerTower": {"playerId": 10, "hp": 100, "armor": 0, "resources": 20, "level": 1},
    "enemyTowers": [{"playerId": 11, "hp": 100, "armor": 0, "level": 1}],
    "diplomacy": [],
    "previousAttacks": []
  }'
```

## Error format

Validation and runtime errors are returned in a consistent JSON format:

```json
{
  "timestamp": "2026-02-09T12:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid request body",
  "path": "/combat"
}
```
