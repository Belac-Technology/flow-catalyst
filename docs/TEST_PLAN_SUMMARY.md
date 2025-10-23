# Integration & E2E Test Plan - Quick Reference

## Overview

**Goal**: Add comprehensive integration and E2E tests to validate the complete message routing system.

**Current State**: 124 unit tests ✅, 8 integration test files with good component coverage ✅
**Gaps**: Missing E2E tests, batch+group FIFO integration tests, resilience tests, load tests

## Test Infrastructure

All integration and E2E tests use **local containerized infrastructure** - no cloud services:

- **LocalStack** (via TestContainers): Local AWS SQS including FIFO queues
- **ActiveMQ** (via TestContainers): Local JMS broker
- **WireMock** (to be added): Mock HTTP endpoints

**Benefits**: No AWS costs, no network dependencies, fast CI/CD, runs offline

---

## Test Categories & Priorities

### 🔴 **Phase 1: E2E Infrastructure (HIGH Priority)**
**Timeline**: Week 1 (5 days)

| Task | File | Estimated Time |
|------|------|----------------|
| Create WireMock test resource | `WireMockTestResource.java` | 2 days |
| Implement basic E2E test | `CompleteEndToEndTest.java` | 3 days |

**Deliverables**:
- ✅ Mock HTTP endpoints for testing
- ✅ Complete message flow validation (Queue → Endpoint)
- ✅ Failure and retry testing
- ✅ Multi-queue routing validation
- ✅ Metrics lifecycle verification

---

### 🔴 **Phase 2: Batch+Group FIFO (HIGH Priority)**
**Timeline**: Week 2 (5 days)

| Task | File | Estimated Time |
|------|------|----------------|
| Create FIFO integration test | `BatchGroupFifoIntegrationTest.java` | 3 days |
| Debug and validate | - | 2 days |

**Deliverables**:
- ✅ LocalStack SQS FIFO queue testing
- ✅ FIFO ordering validation
- ✅ Cascading nack behavior
- ✅ Concurrent group processing

**Infrastructure**: Uses LocalStack SQS FIFO queues (`.fifo` suffix) with `messageGroupId` - no real AWS required

---

### 🟡 **Phase 3: Resilience Tests (MEDIUM Priority)**
**Timeline**: Week 3 (5 days)

| Task | File | Estimated Time |
|------|------|----------------|
| Create resilience test class | `ResilienceIntegrationTest.java` | 2 days |
| Implement all scenarios | - | 3 days |

**Scenarios**:
1. SQS connection loss & recovery
2. ActiveMQ connection loss & recovery
3. HTTP endpoint timeout handling
4. Error responses (4xx, 5xx)
5. Rate limit enforcement
6. Pool queue saturation

**Key Focus**: System recovery and graceful degradation

---

### 🟢 **Phase 4: Performance Tests (LOW Priority)**
**Timeline**: Week 4 (3 days)

| Task | File | Estimated Time |
|------|------|----------------|
| Create load test class | `LoadPerformanceTest.java` | 2 days |
| Establish baselines | - | 1 day |

**Scenarios**:
1. High throughput (1000+ messages)
2. Multi-pool concurrent load
3. Memory stability under sustained load

**Tag**: `@Tag("performance")` - run separately from main test suite

---

### 📚 **Phase 5: Documentation (LOW Priority)**
**Timeline**: Week 4 (2 days)

**Updates**:
- Enhance `TESTING_GUIDE.md` with E2E patterns
- Create `E2ETestUtils.java` helper class
- Document WireMock usage patterns
- Add FIFO queue testing guidelines

---

## Test Count Targets

| Category | Current | Target | New Tests |
|----------|---------|--------|-----------|
| Unit Tests | 124 | 124 | 0 |
| Component Integration | 30 | 30 | 0 |
| E2E Tests | 1 (placeholder) | 5 | +4 |
| Batch+Group FIFO Integration | 0 | 3 | +3 |
| Resilience Tests | 0 | 6 | +6 |
| Performance Tests | 1 | 4 | +3 |
| **Total** | **156** | **172** | **+16** |

---

## Quick Reference: Test Files to Create

### New Test Files
```
src/test/java/tech/flowcatalyst/messagerouter/integration/
├── WireMockTestResource.java          (NEW - E2E infrastructure)
├── CompleteEndToEndTest.java           (NEW - 4 E2E tests)
├── BatchGroupFifoIntegrationTest.java  (NEW - 3 FIFO tests)
├── ResilienceIntegrationTest.java      (NEW - 6 resilience tests)
└── LoadPerformanceTest.java            (NEW - 3 performance tests)

src/test/java/tech/flowcatalyst/messagerouter/integration/
└── E2ETestUtils.java                   (NEW - helper utilities)
```

### Existing Files to Update
```
src/test/java/tech/flowcatalyst/messagerouter/test/
└── TESTING_GUIDE.md                    (UPDATE - add E2E section)

core/flowcatalyst-message-router/build.gradle.kts
└── (UPDATE - add WireMock dependency)
```

---

## Dependencies to Add

Add to `build.gradle.kts`:
```kotlin
testImplementation("org.wiremock:wiremock:3.3.1")
```

*Note*: TestContainers and Awaitility already present

---

## Success Metrics

### Test Quality
- ✅ All tests pass consistently (< 1% flake rate)
- ✅ E2E tests complete in < 5 minutes
- ✅ Integration tests are isolated (no shared state)
- ✅ Performance tests establish baseline metrics

### Coverage
- ✅ E2E coverage for complete message flow
- ✅ Batch+group FIFO validated with real queues
- ✅ Resilience scenarios prove recovery capability
- ✅ Performance baselines documented

### Documentation
- ✅ All test files have comprehensive Javadoc
- ✅ Testing guide updated with E2E patterns
- ✅ Utility classes documented

---

## Implementation Order

### Week 1: Foundation
1. Add WireMock dependency to `build.gradle.kts`
2. Create `WireMockTestResource.java`
3. Implement `CompleteEndToEndTest.java` (4 tests)
4. Verify all E2E tests pass

### Week 2: Core Integration
5. Create `BatchGroupFifoIntegrationTest.java`
6. Implement FIFO ordering test
7. Implement cascading nack test
8. Implement concurrent groups test
9. Debug and stabilize

### Week 3: Resilience
10. Create `ResilienceIntegrationTest.java`
11. Implement broker connection tests (SQS, ActiveMQ)
12. Implement HTTP failure tests (timeout, errors)
13. Implement backpressure tests (rate limit, saturation)

### Week 4: Performance & Docs
14. Create `LoadPerformanceTest.java`
15. Implement throughput test
16. Implement multi-pool test
17. Implement memory stability test
18. Create `E2ETestUtils.java`
19. Update `TESTING_GUIDE.md`

---

## Key Testing Patterns

### E2E Test Pattern
```java
@QuarkusTest
@QuarkusTestResource(LocalStackTestResource.class)
@QuarkusTestResource(WireMockTestResource.class)
@Tag("e2e")
class MyE2ETest {
    // Given: Setup infrastructure
    // When: Send message to real queue
    // Then: Verify end-to-end behavior with real components
}
```

### FIFO Integration Pattern
```java
@QuarkusTest
@QuarkusTestResource(LocalStackTestResource.class)  // Provides local SQS
@Tag("integration")
class MyFifoTest {
    @Inject
    SqsClient sqsClient;  // Auto-configured to use LocalStack

    // Create SQS FIFO queue in LocalStack
    String queueUrl = sqsClient.createQueue(CreateQueueRequest.builder()
        .queueName("test-" + UUID.randomUUID() + ".fifo")
        .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true"))
        .build()).queueUrl();

// Send with messageGroupId
sqsClient.sendMessage(SendMessageRequest.builder()
    .queueUrl(queueUrl)
    .messageBody(messageJson)
    .messageGroupId("order-12345")
    .messageDeduplicationId(UUID.randomUUID().toString())
    .build());
```

### Resilience Test Pattern
```java
// Simulate failure
localStackContainer.pause();

// Verify detection
await().untilAsserted(() -> assertFalse(healthService.isHealthy()));

// Simulate recovery
localStackContainer.unpause();

// Verify reconnection
await().untilAsserted(() -> assertTrue(healthService.isHealthy()));
```

### Performance Test Pattern
```java
@Test
@Tag("performance")
void shouldMeetPerformanceTarget() {
    long startTime = System.currentTimeMillis();

    // Execute load
    for (int i = 0; i < 1000; i++) {
        sendMessage();
    }

    await().until(() -> allMessagesProcessed());
    long duration = System.currentTimeMillis() - startTime;

    // Assert performance
    assertTrue(duration < 30000, "Should complete in < 30s");
}
```

---

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| Flaky tests due to timing | Use Awaitility with generous timeouts |
| TestContainers resource usage | Reuse containers, parallel execution where safe |
| WireMock port conflicts | Use dynamic ports, proper cleanup |
| Long execution time | Tag performance tests separately |
| Network issues in CI | Retry logic for container startup |

---

## CI/CD Integration

### Test Execution Strategy
```bash
# Unit tests (fast - every commit)
./gradlew test --tests '*Test' --tests '!*IntegrationTest'

# Integration tests (slower - pre-merge)
./gradlew test --tests '*IntegrationTest' --tests '!*PerformanceTest'

# Performance tests (slowest - nightly)
./gradlew test --tests '*PerformanceTest'

# All tests
./gradlew test
```

---

## Quick Links

- **Detailed Plan**: [INTEGRATION_E2E_TEST_PLAN.md](./INTEGRATION_E2E_TEST_PLAN.md)
- **Testing Guide**: [../core/flowcatalyst-message-router/src/test/java/tech/flowcatalyst/messagerouter/test/TESTING_GUIDE.md](../core/flowcatalyst-message-router/src/test/java/tech/flowcatalyst/messagerouter/test/TESTING_GUIDE.md)
- **Existing Integration Tests**: `../core/flowcatalyst-message-router/src/test/java/tech/flowcatalyst/messagerouter/integration/`

---

**Status**: 📋 Planning Complete - Ready for Implementation
**Next Step**: Begin Week 1 - Create WireMock test resource
