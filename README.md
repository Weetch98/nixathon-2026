# Nixathon 2026

## Requirements

- Java 21
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
