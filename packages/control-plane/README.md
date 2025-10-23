# FlowCatalyst Control Plane

Web-based control plane for managing FlowCatalyst platform resources including event types, subscriptions, and dispatch jobs.

## Features

- **Dashboard**: Overview of platform metrics and activity
- **Event Types**: Register and manage event type definitions
- **Subscriptions**: Create and monitor event subscriptions
- **Dispatch Jobs**: Monitor and troubleshoot message dispatch jobs

## Tech Stack

- **Vue 3** with Composition API
- **TypeScript** for type safety
- **Vite** for fast development and optimized builds
- **Vue Router** for navigation
- **Pinia** for state management
- **Tailwind CSS** for styling
- **@flowcatalyst/ui-components** for shared UI components

## Development

```bash
# Install dependencies
bun install

# Start dev server (standalone)
bun run dev

# Type check
bun run type-check

# Build for production
bun run build
```

## Integration with BFFE

This frontend is designed to be served by the FlowCatalyst Control Plane BFFE (Backend For Frontend) using Quarkus Web Bundler. In production, the BFFE will:

1. Build this frontend automatically
2. Serve the static assets
3. Provide `/api/*` endpoints
4. Handle session-based authentication

## Project Structure

```
src/
├── api/           # API client utilities
├── assets/        # Static assets
├── components/    # Vue components
├── router/        # Vue Router configuration
├── stores/        # Pinia stores
├── views/         # Page-level components
├── App.vue        # Root component
├── main.ts        # Application entry point
└── style.css      # Global styles
```

## Development Workflow

### Standalone Development
Run the frontend in isolation with API proxy:
```bash
bun run dev
# Proxies /api/* to http://localhost:8080
```

### Integrated Development
Run with Quarkus BFFE (recommended):
```bash
cd ../../
./gradlew :core:flowcatalyst-control-plane-bffe:quarkusDev
# Serves frontend + backend on http://localhost:8080
```

## License

Apache-2.0
