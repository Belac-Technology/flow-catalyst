.PHONY: build-router-latest

# ECR Registry configuration
ECR_REGISTRY ?= 392314734354.dkr.ecr.eu-west-1.amazonaws.com
ECR_REPO ?= flowcatalyst/router
ECR_IMAGE ?= $(ECR_REGISTRY)/$(ECR_REPO):latest

# Router build configuration
ROUTER_PROJECT ?= core/flowcatalyst-message-router
ROUTER_DOCKERFILE ?= $(ROUTER_PROJECT)/src/main/docker/Dockerfile
ROUTER_BUILD_DIR ?= $(ROUTER_PROJECT)/build
ROUTER_IMAGE_NAME ?= flowcatalyst/router
ROUTER_IMAGE_TAG ?= latest
ROUTER_LOCAL_IMAGE ?= $(ROUTER_IMAGE_NAME):$(ROUTER_IMAGE_TAG)

# Build targets
build-router-latest: clean-router-build build-router-jar docker-build-router docker-tag-router
	@echo "✓ Router image built and tagged successfully"
	@echo "  Local image: $(ROUTER_LOCAL_IMAGE)"
	@echo "  ECR image: $(ECR_IMAGE)"
	@echo ""
	@echo "To push to ECR, run: make push-router"

clean-router-build:
	@echo "Cleaning router build directory..."
	rm -rf $(ROUTER_BUILD_DIR)

build-router-jar:
	@echo "Building router JAR (skipping tests)..."
	./gradlew clean :core:flowcatalyst-message-router:build -x test -x integrationTest

docker-build-router:
	@echo "Building Docker image with platform linux/amd64..."
	docker build --platform linux/amd64 --no-cache \
		-f $(ROUTER_DOCKERFILE) \
		-t $(ROUTER_LOCAL_IMAGE) $(ROUTER_PROJECT)

docker-tag-router:
	@echo "Tagging image for ECR..."
	docker tag $(ROUTER_LOCAL_IMAGE) $(ECR_IMAGE)

push-router:
	@echo "Pushing router image to ECR..."
	docker push $(ECR_IMAGE)
	@echo "✓ Image pushed to ECR"

verify-router-image:
	@echo "Verifying router image..."
	docker inspect $(ECR_IMAGE) | grep -E "Architecture|Os"

clean-all: clean-router-build
	@echo "Clean complete"

help:
	@echo "Available targets:"
	@echo "  build-router-latest  - Full build: clean, gradle build, docker build, tag (recommended)"
	@echo "  push-router          - Push image to ECR"
	@echo "  verify-router-image  - Verify image architecture"
	@echo "  clean-all            - Clean build artifacts"
