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

# =============================================================================
# JWT Key Generation
# =============================================================================

JWT_KEYS_DIR ?= .keys
JWT_PRIVATE_KEY ?= $(JWT_KEYS_DIR)/jwt-private.pem
JWT_PUBLIC_KEY ?= $(JWT_KEYS_DIR)/jwt-public.pem

generate-jwt-keys: $(JWT_KEYS_DIR)
	@echo "Generating RSA key pair for JWT signing..."
	openssl genrsa -out $(JWT_PRIVATE_KEY) 2048
	openssl rsa -in $(JWT_PRIVATE_KEY) -pubout -out $(JWT_PUBLIC_KEY)
	chmod 600 $(JWT_PRIVATE_KEY)
	chmod 644 $(JWT_PUBLIC_KEY)
	@echo "✓ JWT keys generated:"
	@echo "  Private key: $(JWT_PRIVATE_KEY)"
	@echo "  Public key:  $(JWT_PUBLIC_KEY)"
	@echo ""
	@echo "Configure in application.properties or environment:"
	@echo "  FLOWCATALYST_JWT_PRIVATE_KEY_PATH=$(shell pwd)/$(JWT_PRIVATE_KEY)"
	@echo "  FLOWCATALYST_JWT_PUBLIC_KEY_PATH=$(shell pwd)/$(JWT_PUBLIC_KEY)"

$(JWT_KEYS_DIR):
	mkdir -p $(JWT_KEYS_DIR)

clean-jwt-keys:
	@echo "Removing JWT keys..."
	rm -rf $(JWT_KEYS_DIR)

help:
	@echo "Available targets:"
	@echo ""
	@echo "Router:"
	@echo "  build-router-latest  - Full build: clean, gradle build, docker build, tag"
	@echo "  push-router          - Push image to ECR"
	@echo "  verify-router-image  - Verify image architecture"
	@echo ""
	@echo "JWT Keys:"
	@echo "  generate-jwt-keys    - Generate RSA key pair for JWT signing"
	@echo "  clean-jwt-keys       - Remove generated JWT keys"
	@echo ""
	@echo "Cleanup:"
	@echo "  clean-all            - Clean build artifacts"
