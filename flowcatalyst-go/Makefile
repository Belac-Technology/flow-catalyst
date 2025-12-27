.PHONY: all build build-all build-dev build-production build-router build-platform build-stream build-outbox clean test test-integration test-all lint run dev dev-db dev-db-stop dev-platform dev-stream dev-services dev-full dev-platform-watch dev-stream-watch tidy swagger

# Version information
VERSION := $(shell git describe --tags --always --dirty 2>/dev/null || echo "dev")
BUILD_TIME := $(shell date -u +"%Y-%m-%dT%H:%M:%SZ")
LDFLAGS := -s -w -X main.version=$(VERSION) -X main.buildTime=$(BUILD_TIME)

# Binary names
BINARY_DEV := flowcatalyst
BINARY_ROUTER := fc-router
BINARY_PLATFORM := fc-platform
BINARY_STREAM := fc-stream
BINARY_OUTBOX := fc-outbox

# Build platforms
PLATFORMS := \
	darwin/amd64 \
	darwin/arm64 \
	linux/amd64 \
	linux/arm64 \
	windows/amd64 \
	windows/arm64

# Default target
all: build

# ============================================================================
# Development Builds (combined binary with embedded NATS, outbox processor)
# ============================================================================

# Build development binary for current platform
build: build-dev

build-dev:
	go build -ldflags "$(LDFLAGS)" -o bin/$(BINARY_DEV) ./cmd/flowcatalyst

# Run the combined development application (all-in-one)
run: build-dev
	./bin/$(BINARY_DEV)

# Run combined dev binary with console logging
dev:
	FLOWCATALYST_DEV=true go run ./cmd/flowcatalyst

# ============================================================================
# Development Environment (separate services like production)
# ============================================================================

# MongoDB connection for local development
MONGODB_URI := mongodb://localhost:27017/?replicaSet=rs0&directConnection=true
MONGODB_DATABASE := flowcatalyst
DEV_ENV := FLOWCATALYST_DEV=true MONGODB_URI="$(MONGODB_URI)" MONGODB_DATABASE=$(MONGODB_DATABASE)

# Start MongoDB via docker compose
dev-db:
	docker compose -f ../docker-compose.dev.yml up -d
	@echo "Waiting for MongoDB to be ready..."
	@sleep 5
	@echo "MongoDB ready at mongodb://localhost:27017"

# Stop MongoDB
dev-db-stop:
	docker compose -f ../docker-compose.dev.yml down

# Run platform API (port 8080)
dev-platform: build-platform
	$(DEV_ENV) HTTP_PORT=8080 ./bin/$(BINARY_PLATFORM)

# Run stream processor (port 8082)
dev-stream: build-stream
	$(DEV_ENV) HTTP_PORT=8082 ./bin/$(BINARY_STREAM)

# Run platform + stream together (requires `goreman` or run in separate terminals)
dev-services: build-platform build-stream
	@echo "Starting Platform API on :8080 and Stream Processor on :8082"
	@echo "Run these in separate terminals:"
	@echo "  make dev-platform"
	@echo "  make dev-stream"
	@echo ""
	@echo "Or install goreman: go install github.com/mattn/goreman@latest"
	@echo "Then run: goreman -f Procfile.dev start"

# Full dev environment: start DB + services
dev-full: dev-db
	@echo ""
	@echo "MongoDB started. Now run services in separate terminals:"
	@echo "  make dev-platform   # Platform API on :8080"
	@echo "  make dev-stream     # Stream Processor on :8082"
	@echo ""
	@echo "Or use goreman: goreman -f Procfile.dev start"

# Hot reload development using air (install: go install github.com/air-verse/air@latest)
dev-platform-watch:
	$(DEV_ENV) HTTP_PORT=8080 air -c .air.platform.toml

dev-stream-watch:
	$(DEV_ENV) HTTP_PORT=8082 air -c .air.stream.toml

# ============================================================================
# Production Builds (separate binaries for microservices deployment)
# ============================================================================

# Build all production binaries for current platform
build-production: build-router build-platform build-stream build-outbox

# Build message router binary
build-router:
	go build -ldflags "$(LDFLAGS)" -o bin/$(BINARY_ROUTER) ./cmd/router

# Build platform API binary
build-platform:
	go build -ldflags "$(LDFLAGS)" -o bin/$(BINARY_PLATFORM) ./cmd/platform

# Build stream processor binary
build-stream:
	go build -ldflags "$(LDFLAGS)" -o bin/$(BINARY_STREAM) ./cmd/stream

# Build outbox processor binary
build-outbox:
	go build -ldflags "$(LDFLAGS)" -o bin/$(BINARY_OUTBOX) ./cmd/outbox

# ============================================================================
# Cross-Platform Builds
# ============================================================================

# Build all binaries for all platforms
build-all:
	@mkdir -p dist
	@echo "Building development binary for all platforms..."
	@for platform in $(PLATFORMS); do \
		GOOS=$${platform%/*} GOARCH=$${platform#*/} \
		go build -ldflags "$(LDFLAGS)" \
		-o dist/$(BINARY_DEV)-$${platform%/*}-$${platform#*/}$$([ $${platform%/*} = windows ] && echo .exe) \
		./cmd/flowcatalyst && \
		echo "  Built: $(BINARY_DEV)-$${platform%/*}-$${platform#*/}"; \
	done
	@echo "\nBuilding production binaries for all platforms..."
	@for platform in $(PLATFORMS); do \
		GOOS=$${platform%/*} GOARCH=$${platform#*/} \
		go build -ldflags "$(LDFLAGS)" \
		-o dist/$(BINARY_ROUTER)-$${platform%/*}-$${platform#*/}$$([ $${platform%/*} = windows ] && echo .exe) \
		./cmd/router && \
		echo "  Built: $(BINARY_ROUTER)-$${platform%/*}-$${platform#*/}"; \
	done
	@for platform in $(PLATFORMS); do \
		GOOS=$${platform%/*} GOARCH=$${platform#*/} \
		go build -ldflags "$(LDFLAGS)" \
		-o dist/$(BINARY_PLATFORM)-$${platform%/*}-$${platform#*/}$$([ $${platform%/*} = windows ] && echo .exe) \
		./cmd/platform && \
		echo "  Built: $(BINARY_PLATFORM)-$${platform%/*}-$${platform#*/}"; \
	done
	@for platform in $(PLATFORMS); do \
		GOOS=$${platform%/*} GOARCH=$${platform#*/} \
		go build -ldflags "$(LDFLAGS)" \
		-o dist/$(BINARY_STREAM)-$${platform%/*}-$${platform#*/}$$([ $${platform%/*} = windows ] && echo .exe) \
		./cmd/stream && \
		echo "  Built: $(BINARY_STREAM)-$${platform%/*}-$${platform#*/}"; \
	done
	@for platform in $(PLATFORMS); do \
		GOOS=$${platform%/*} GOARCH=$${platform#*/} \
		go build -ldflags "$(LDFLAGS)" \
		-o dist/$(BINARY_OUTBOX)-$${platform%/*}-$${platform#*/}$$([ $${platform%/*} = windows ] && echo .exe) \
		./cmd/outbox && \
		echo "  Built: $(BINARY_OUTBOX)-$${platform%/*}-$${platform#*/}"; \
	done

# Create release archives
release: build-all
	@cd dist && for f in fc-*; do \
		if [ -f "$$f" ]; then \
			tar -czf "$$f.tar.gz" "$$f" && \
			echo "Archived: $$f.tar.gz"; \
		fi \
	done

# ============================================================================
# Docker Builds (for container deployments)
# ============================================================================

docker-router:
	docker build -f Dockerfile.router -t flowcatalyst/router:$(VERSION) .

docker-platform:
	docker build -f Dockerfile.platform -t flowcatalyst/platform:$(VERSION) .

docker-stream:
	docker build -f Dockerfile.stream -t flowcatalyst/stream:$(VERSION) .

docker-outbox:
	docker build -f Dockerfile.outbox -t flowcatalyst/outbox:$(VERSION) .

docker-all: docker-router docker-platform docker-stream docker-outbox

# ============================================================================
# Testing
# ============================================================================

# Run tests
test:
	go test -v -race -cover ./...

# Run tests with coverage report
test-coverage:
	go test -v -race -coverprofile=coverage.out ./...
	go tool cover -html=coverage.out -o coverage.html

# Run integration tests (requires Docker)
test-integration:
	go test -v -tags=integration -timeout=10m ./internal/queue/sqs/...

# Run all tests (unit + integration)
test-all: test test-integration

# ============================================================================
# Code Quality
# ============================================================================

# Run linter
lint:
	golangci-lint run ./...

# Format code
fmt:
	gofmt -s -w .
	goimports -w .

# Security scan
security:
	gosec ./...

# ============================================================================
# Dependencies
# ============================================================================

# Tidy dependencies
tidy:
	go mod tidy

# Download dependencies
deps:
	go mod download

# Verify module
verify:
	go mod verify

# Check for outdated dependencies
outdated:
	go list -u -m all

# ============================================================================
# Code Generation
# ============================================================================

# Generate code (if needed)
generate:
	go generate ./...

# Generate Swagger documentation
swagger:
	@which swag > /dev/null || go install github.com/swaggo/swag/cmd/swag@latest
	swag init -g cmd/flowcatalyst/main.go -o docs
	@echo "Swagger docs generated in docs/"

# ============================================================================
# Cleanup
# ============================================================================

# Clean build artifacts
clean:
	rm -rf bin/ dist/
	go clean

# ============================================================================
# Utilities
# ============================================================================

# Show binary sizes
sizes: build-production
	@echo "\nProduction binary sizes:"
	@ls -lh bin/fc-* 2>/dev/null | awk '{print $$5, $$9}'

# ============================================================================
# Help
# ============================================================================

help:
	@echo "FlowCatalyst Build System"
	@echo "========================="
	@echo ""
	@echo "Quick Start (all-in-one dev binary):"
	@echo "  make dev             - Run combined binary with embedded NATS"
	@echo ""
	@echo "Development Environment (separate services):"
	@echo "  make dev-db          - Start MongoDB via Docker Compose"
	@echo "  make dev-db-stop     - Stop MongoDB"
	@echo "  make dev-platform    - Run Platform API on :8080"
	@echo "  make dev-stream      - Run Stream Processor on :8082"
	@echo "  make dev-full        - Start DB + show service commands"
	@echo ""
	@echo "  With hot reload (requires: go install github.com/air-verse/air@latest):"
	@echo "  make dev-platform-watch  - Platform with hot reload"
	@echo "  make dev-stream-watch    - Stream with hot reload"
	@echo ""
	@echo "  With goreman (requires: go install github.com/mattn/goreman@latest):"
	@echo "  goreman -f Procfile.dev start  - Run both services together"
	@echo ""
	@echo "Production Builds:"
	@echo "  build-production     - Build all production binaries"
	@echo "  build-router         - Message router (fc-router)"
	@echo "  build-platform       - Platform API (fc-platform)"
	@echo "  build-stream         - Stream processor (fc-stream)"
	@echo "  build-outbox         - Outbox processor (fc-outbox)"
	@echo ""
	@echo "Cross-Platform:"
	@echo "  build-all            - Build all binaries for all platforms"
	@echo "  release              - Build and create tar.gz archives"
	@echo ""
	@echo "Docker:"
	@echo "  docker-router        - Build router Docker image"
	@echo "  docker-platform      - Build platform Docker image"
	@echo "  docker-stream        - Build stream Docker image"
	@echo "  docker-outbox        - Build outbox Docker image"
	@echo "  docker-all           - Build all Docker images"
	@echo ""
	@echo "Testing:"
	@echo "  test                 - Run unit tests with race detector"
	@echo "  test-coverage        - Run tests with HTML coverage report"
	@echo "  test-integration     - Run integration tests (requires Docker)"
	@echo "  test-all             - Run all tests"
	@echo ""
	@echo "Code Quality:"
	@echo "  lint                 - Run golangci-lint"
	@echo "  fmt                  - Format code"
	@echo "  security             - Run security scan"
	@echo ""
	@echo "Other:"
	@echo "  tidy                 - Tidy go.mod"
	@echo "  swagger              - Generate Swagger/OpenAPI docs"
	@echo "  sizes                - Show production binary sizes"
	@echo "  clean                - Remove build artifacts"
