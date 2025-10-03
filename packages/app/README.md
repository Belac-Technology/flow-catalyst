# FlowCatalyst App

Web-based application for managing FlowCatalyst platform resources including event types, subscriptions, and dispatch jobs.

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

# Run tests
bun run test

# Run tests with UI
bun run test:ui

# Run tests with coverage
bun run test:coverage

# Build for production
bun run build
```

## Integration with BFFE

This frontend is designed to be served by the FlowCatalyst BFFE (Backend For Frontend).

**Note**: Quinoa integration is currently disabled. The frontend and backend are built separately:

1. Build frontend: `cd packages/app && bun run build`
2. Build backend: `./gradlew :core:flowcatalyst-bffe:quarkusBuild`
3. The BFFE will serve static assets and provide `/api/*` endpoints

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

### Standalone Development (Recommended)
Run the frontend in isolation with API proxy:
```bash
cd packages/app
bun run dev
# Frontend: http://localhost:5173
# Proxies /api/* to http://localhost:8080 (start BFFE separately)
```

### Run Backend Separately
```bash
./gradlew :core:flowcatalyst-bffe:quarkusDev
# Backend: http://localhost:8080
```

### Run Both Together
```bash
# Terminal 1: Start BFFE
./gradlew :core:flowcatalyst-bffe:quarkusDev

# Terminal 2: Start frontend
cd packages/app && bun run dev
```

## License

Apache-2.0
