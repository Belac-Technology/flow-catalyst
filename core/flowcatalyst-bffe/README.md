# FlowCatalyst Control Plane BFFE

Backend For Frontend (BFFE) for the FlowCatalyst Control Plane. This Quarkus application serves the Vue.js frontend and provides REST API endpoints for platform management.

## Features

- **Quarkus Web Bundler**: Automatically builds and serves the Vue frontend
- **Session-based Authentication**: Secure session management with OIDC
- **REST API**: Backend endpoints for platform operations
- **Single Origin**: Frontend and backend on same domain (no CORS issues)

## Tech Stack

- **Quarkus**: Backend framework
- **Quarkus Web Bundler**: Frontend integration
- **Quarkus OIDC**: Authentication
- **RESTEasy Reactive**: REST endpoints

## Development

### Prerequisites

- Java 21
- Bun (for frontend)

### Running in Dev Mode

```bash
# From project root
./gradlew :core:flowcatalyst-control-plane-bffe:quarkusDev
```

This will:
1. Start Quarkus in dev mode on port 8080
2. Detect the frontend in `src/main/webui`
3. Run `bun install` and `bun run dev` automatically
4. Serve frontend at `http://localhost:8080`
5. Serve API endpoints at `http://localhost:8080/api/*`
6. Enable hot reload for both backend and frontend

### Project Structure

```
src/
├── main/
│   ├── java/
│   │   └── tech/flowcatalyst/controlplane/
│   │       └── api/          # REST endpoints
│   ├── resources/
│   │   └── application.properties
│   └── webui/                # Symlink to packages/control-plane
└── test/
    └── java/
```

## API Endpoints

### Health Check
```
GET /api/health
```

### Platform Stats
```
GET /api/stats
```
Returns:
```json
{
  "eventTypes": 0,
  "subscriptions": 0,
  "messagesProcessed": 0
}
```

## Configuration

See `src/main/resources/application.properties` for configuration options.

## Building for Production

```bash
./gradlew :core:flowcatalyst-control-plane-bffe:build
```

This will:
1. Build the frontend (`bun run build`)
2. Package frontend assets into the JAR
3. Create a production-ready Quarkus application

## Deployment

The built JAR contains both backend and frontend:

```bash
java -jar target/quarkus-app/quarkus-run.jar
```

Or build a native executable:

```bash
./gradlew :core:flowcatalyst-control-plane-bffe:build -Dquarkus.package.type=native
```

## License

Apache-2.0
