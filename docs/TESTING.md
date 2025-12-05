# FlowCatalyst Testing Guide

## Table of Contents
- [Overview](#overview)
- [Test Architecture](#test-architecture)
- [Running Tests](#running-tests)
- [Writing Tests](#writing-tests)
- [Test Patterns](#test-patterns)
- [Coverage](#coverage)
- [Troubleshooting](#troubleshooting)

## Overview

FlowCatalyst has a comprehensive test suite covering:
- **Unit Tests**: Individual component testing with mocks
- **Integration Tests**: Component interaction testing
- **End-to-End Tests**: Full system flow testing with TestContainers

### Test Statistics
- **Total Test Classes**: 10
- **Total Tests**: 80+
- **Test Success Rate**: 100%
- **Coverage**: HIGH and MEDIUM-HIGH priority items fully covered

## Test Architecture

### Test Organization

```
src/test/java/tech/flowcatalyst/messagerouter/
├── consumer/
│   ├── ActiveMqQueueConsumerTest.java    # NEW: ActiveMQ consumer tests
│   └── SqsQueueConsumerTest.java         # SQS consumer tests
├── endpoint/
│   └── MonitoringResourceTest.java       # NEW: Monitoring REST API tests
├── integration/
│   ├── EndToEndIntegrationTest.java      # Full system E2E tests
│   ├── RateLimiterIntegrationTest.java   # Rate limiter integration
│   └── SqsLocalStackIntegrationTest.java # Real SQS with LocalStack
├── manager/
│   └── QueueManagerTest.java             # NEW: Message routing tests
├── mediator/
│   └── HttpMediatorTest.java             # ENHANCED: HTTP mediation tests
├── metrics/
│   └── MicrometerQueueMetricsServiceTest.java # NEW: Metrics service tests
└── pool/
    └── ProcessPoolImplTest.java          # Process pool execution tests
```

### Test Layers

#### Layer 1: Unit Tests (White-box)
Test individual components in isolation with mocked dependencies.

**Examples:**
- `QueueManagerTest` - Tests routing logic with mocked pools
- `HttpMediatorTest` - Tests HTTP mediation with test endpoints
- `MicrometerQueueMetricsServiceTest` - Tests metrics with SimpleMeterRegistry

#### Layer 2: Integration Tests (Gray-box)
Test component interactions with real implementations where possible.

**Examples:**
- `ActiveMqQueueConsumerTest` - JMS mocks but real consumer logic
- `SqsQueueConsumerTest` - AWS SDK mocks but real consumer logic
- `RateLimiterIntegrationTest` - Real Resilience4j rate limiters

#### Layer 3: End-to-End Tests (Black-box)
Test complete system flows with TestContainers for external dependencies.

**Examples:**
- `SqsLocalStackIntegrationTest` - Real LocalStack SQS container
- `EndToEndIntegrationTest` - Full message flow (simplified)

## Running Tests

### Quick Start

```bash
# Run all tests
./gradlew clean test

# Run only unit tests (fast)
./gradlew test --tests '*' --exclude-task integrationTest

# Run only integration tests
./gradlew integrationTest
```

### Specific Test Execution

```bash
# Run specific test class
./gradlew test --tests QueueManagerTest

# Run specific test method
./gradlew test --tests QueueManagerTest.shouldRouteMessageToCorrectPool

# Run multiple test classes
./gradlew test --tests QueueManagerTest --tests HttpMediatorTest

# Run with pattern matching
./gradlew test --tests '*Consumer*'
```

### Running Tests by Component

```bash
# Queue and Consumer tests
./gradlew test --tests '*QueueConsumerTest' --tests 'QueueManagerTest'

# Metrics tests
./gradlew test --tests '*MetricsTest'

# REST endpoint tests
./gradlew test --tests '*ResourceTest'

# Pool and execution tests
./gradlew test --tests '*PoolTest'
```

### Docker-dependent Tests

Some tests require Docker (TestContainers):

```bash
# Check Docker is running
docker ps

# Run tests with LocalStack
./gradlew test --tests SqsLocalStackIntegrationTest

# TestContainers will automatically:
# 1. Pull localstack/localstack:3.0 image
# 2. Start container
# 3. Configure SQS
# 4. Run tests
# 5. Stop container
```

### Test Output Options

```bash
# Verbose output
./gradlew test --debug

# Quiet mode
./gradlew test --quiet

# Show stack traces for failures
./gradlew test --stacktrace

# Generate test reports
./gradlew test
# Reports in: build/reports/tests/test/
```

## Writing Tests

### Test Structure Template

```java
@QuarkusTest
class MyComponentTest {

    @Inject
    MyComponent component;

    @InjectMock
    MyDependency mockDependency;

    @BeforeEach
    void setUp() {
        // Setup test data and mocks
        reset(mockDependency);
        when(mockDependency.someMethod()).thenReturn(expectedValue);
    }

    @AfterEach
    void tearDown() {
        // Clean up resources if needed
    }

    @Test
    void shouldPerformExpectedBehaviorWhenConditionMet() {
        // Given - Arrange test data
        String input = "test-data";

        // When - Execute the behavior under test
        Result result = component.process(input);

        // Then - Assert expected outcomes
        assertNotNull(result);
        assertEquals(expected, result.getValue());
        verify(mockDependency).someMethod();
    }
}
```

### Naming Conventions

#### Test Class Names
```java
// Component name + "Test"
QueueManagerTest
HttpMediatorTest
ActiveMqQueueConsumerTest
```

#### Test Method Names
```java
// should + ExpectedBehavior + When + StateUnderTest
shouldRouteMessageToCorrectPool()
shouldReturnErrorProcessFor400Response()
shouldHandleQueueFull()
shouldDetectDuplicateMessages()
```

### Given-When-Then Structure

Always structure tests with clear sections:

```java
@Test
void shouldCalculateSuccessRate() {
    // Given - Setup test scenario
    String queueId = "test-queue";
    for (int i = 0; i < 7; i++) {
        metricsService.recordMessageReceived(queueId);
    }
    for (int i = 0; i < 5; i++) {
        metricsService.recordMessageProcessed(queueId, true);
    }
    for (int i = 0; i < 2; i++) {
        metricsService.recordMessageProcessed(queueId, false);
    }

    // When - Execute the behavior
    QueueStats stats = metricsService.getQueueStats(queueId);

    // Then - Assert the outcomes
    assertEquals(5.0 / 7.0, stats.successRate(), 0.01);
}
```

## Test Patterns

### 1. Quarkus Test Extension

Use `@QuarkusTest` for CDI integration:

```java
@QuarkusTest
class MonitoringResourceTest {

    @InjectMock
    QueueMetricsService queueMetricsService;

    @Test
    void shouldGetQueueStats() {
        // Mock returns test data
        when(queueMetricsService.getAllQueueStats())
            .thenReturn(Map.of("queue-1", mockStats));

        // RestAssured tests REST endpoint
        given()
            .when().get("/monitoring/queue-stats")
            .then()
            .statusCode(200)
            .body("'queue-1'.name", equalTo("queue-1"));
    }
}
```

### 2. Mockito for Mocking

#### Creating Mocks

```java
@BeforeEach
void setUp() {
    mockSqsClient = mock(SqsClient.class);
    mockQueueManager = mock(QueueManager.class);
    mockQueueMetrics = mock(QueueMetricsService.class);
}
```

#### Stubbing Method Calls

```java
// Return a value
when(mockSqsClient.receiveMessage(any())).thenReturn(response);

// Return different values on subsequent calls
when(mockMessageConsumer.receive(anyLong()))
    .thenReturn(textMessage)
    .thenReturn(null);

// Throw an exception
when(mockConnectionFactory.createConnection())
    .thenThrow(new JMSException("Connection failed"));
```

#### Verifying Interactions

```java
// Verify method was called
verify(mockQueueManager).routeMessage(any(), any());

// Verify with specific arguments
verify(mockCallback).ack(message);

// Verify call count
verify(mockMediator, times(2)).process(any());

// Verify never called
verify(mockSqsClient, never()).deleteMessage(any());

// Capture arguments
ArgumentCaptor<MessageCallback> callbackCaptor =
    ArgumentCaptor.forClass(MessageCallback.class);
verify(mockQueueManager).routeMessage(any(), callbackCaptor.capture());
MessageCallback callback = callbackCaptor.getValue();
```

### 3. Awaitility for Async Testing

Test asynchronous behavior:

```java
@Test
void shouldProcessMessageSuccessfully() {
    // Given
    MessagePointer message = new MessagePointer(...);
    when(mockMediator.process(message)).thenReturn(MediationResult.SUCCESS);

    // When
    processPool.start();
    boolean submitted = processPool.submit(message);

    // Then - wait for async processing
    assertTrue(submitted);
    await().untilAsserted(() -> {
        verify(mockMediator).process(message);
        verify(mockCallback).ack(message);
        assertFalse(inPipelineMap.containsKey(message.id()));
    });
}
```

**Awaitility Configuration:**
```java
// With timeout
await()
    .atMost(Duration.ofSeconds(5))
    .untilAsserted(() -> verify(mock).method());

// With polling interval
await()
    .pollInterval(Duration.ofMillis(100))
    .untilAsserted(() -> assertTrue(condition));

// Until condition is true
await().until(() -> list.size() == 5);
```

### 4. RestAssured for REST Testing

Test REST endpoints:

```java
@Test
void shouldGetHealthStatus() {
    // Given
    when(healthStatusService.getHealthStatus())
        .thenReturn(mockHealthStatus);

    // When/Then
    given()
        .when()
            .get("/monitoring/health")
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("status", equalTo("HEALTHY"))
            .body("details.totalQueues", equalTo(2));
}
```

**RestAssured Features:**
```java
// POST with body
given()
    .contentType("application/json")
    .body(requestBody)
    .when()
        .post("/api/endpoint")
    .then()
        .statusCode(201)
        .body("id", notNullValue());

// Query parameters
given()
    .queryParam("hours", 24)
    .when()
        .delete("/monitoring/warnings/old")
    .then()
        .statusCode(200);

// Path parameters
given()
    .when()
        .post("/monitoring/warnings/{id}/acknowledge", "warn-1")
    .then()
        .statusCode(200);

// Extract response
String id = given()
    .when().get("/api/resource")
    .then().extract().path("id");
```

### 5. TestContainers for Real Dependencies

Use real services in tests:

```java
@QuarkusTest
class SqsLocalStackIntegrationTest {

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack:3.0")
    )
    .withServices(LocalStackContainer.Service.SQS)
    .withReuse(true);

    @BeforeAll
    static void setupSqs() {
        SqsClient sqsClient = SqsClient.builder()
            .endpointOverride(localstack.getEndpoint())
            .credentialsProvider(...)
            .build();

        sqsClient.createQueue(CreateQueueRequest.builder()
            .queueName("test-queue")
            .build());
    }

    @Test
    void shouldConsumeFromRealSqs() {
        // Test with real SQS container
    }
}
```

### 6. Reflection for White-Box Testing

Access private fields for testing:

```java
private <T> T getPrivateField(Object obj, String fieldName) throws Exception {
    Field field = obj.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return (T) field.get(obj);
}

@Test
void shouldRouteMessageToCorrectPool() throws Exception {
    // Access private field for assertions
    ConcurrentHashMap<String, ProcessPool> processPools =
        getPrivateField(queueManager, "processPools");

    ProcessPool pool = createAndRegisterPool("TEST-POOL", 5, 100);

    // Now can inspect internal state
    assertTrue(processPools.containsKey("TEST-POOL"));
}
```

## Coverage

### Coverage by Priority

#### ✅ HIGH PRIORITY - 100% Covered
1. **QueueManager** (10 tests)
   - Message routing
   - Duplicate detection
   - Callback management
   - Error handling

2. **HttpMediator** (11 tests)
   - Status code mapping
   - Error handling
   - Authorization
   - Retry/Circuit breaker behavior

3. **ActiveMqQueueConsumer** (14 tests)
   - JMS consumption
   - ACK/NACK behavior
   - Metrics polling
   - Resource cleanup

#### ✅ MEDIUM-HIGH PRIORITY - 100% Covered
1. **MicrometerQueueMetricsService** (15 tests)
   - NEW: Queue metrics (pending, not-visible)
   - Counter/Gauge registration
   - Stats calculation

2. **MonitoringResource** (19 tests)
   - All REST endpoints
   - Error handling
   - JSON validation

3. **ProcessPoolImpl** (10 tests)
   - Execution
   - Concurrency
   - Rate limiting

#### ⚠️ MEDIUM PRIORITY - Partial Coverage
1. **SqsQueueConsumer** (7 tests) - Well covered
2. **Integration Tests** (6+6 tests) - Good coverage

#### ⚠️ LOW PRIORITY - Minimal Coverage
1. Factories (not tested)
2. Services (HealthStatus, Warning, CircuitBreaker)
3. Configuration classes

### Measuring Coverage

```bash
# Generate JaCoCo coverage report
./gradlew clean test jacocoTestReport

# View report
open build/reports/jacoco/test/html/index.html
```

## Troubleshooting

### Common Issues

#### 1. Tests Timing Out

**Problem:** Async tests waiting forever

**Solution:** Use explicit timeouts with Awaitility
```java
await()
    .atMost(Duration.ofSeconds(5))
    .untilAsserted(() -> verify(mock).method());
```

#### 2. TestContainers Docker Issues

**Problem:** "Could not find a valid Docker environment"

**Solutions:**
```bash
# Check Docker is running
docker ps

# Check Docker socket
ls -la /var/run/docker.sock

# Set DOCKER_HOST if needed
export DOCKER_HOST=unix:///var/run/docker.sock
```

#### 3. Quarkus CDI Injection Failures

**Problem:** `@Inject` or `@InjectMock` not working

**Solution:** Ensure class is annotated with `@QuarkusTest`
```java
@QuarkusTest  // Required!
class MyTest {
    @Inject
    MyService service;  // Now works
}
```

#### 4. Mock Not Being Used

**Problem:** Real implementation called instead of mock

**Solution:** Use `@InjectMock` not `@Mock`
```java
@InjectMock  // Correct for Quarkus
QueueMetricsService mockService;

// NOT
@Mock  // Wrong - this won't inject into CDI
QueueMetricsService mockService;
```

#### 5. Concurrent Test Failures

**Problem:** Tests pass individually but fail when run together

**Solution:** Ensure proper cleanup in `@AfterEach`
```java
@AfterEach
void tearDown() {
    reset(allMocks...);
    if (processPool != null) {
        processPool.drain();
    }
}
```

#### 6. Reflection Access Denied

**Problem:** Cannot access private field

**Solution:** Set accessible before accessing
```java
Field field = obj.getClass().getDeclaredField(fieldName);
field.setAccessible(true);  // Required!
return field.get(obj);
```

### Debug Tips

```bash
# Run single test with debug logging
./gradlew test --tests QueueManagerTest --debug

# Enable Quarkus debug logging
./gradlew test -Dquarkus.log.level=DEBUG

# Debug specific package
./gradlew test -Dquarkus.log.category.\"tech.flowcatalyst\".level=DEBUG

# Remote debugging
./gradlew test --debug-jvm

# Then attach debugger to port 5005
```

## Best Practices

### DO ✅
- Use `@QuarkusTest` for integration with CDI
- Use `@InjectMock` for mocking CDI beans
- Use Awaitility for async assertions
- Use RestAssured for REST endpoint testing
- Clean up resources in `@AfterEach`
- Use descriptive test names
- Follow Given-When-Then structure
- Test both happy path and error cases
- Use TestContainers for real external dependencies

### DON'T ❌
- Don't use `@Mock` with Quarkus (use `@InjectMock`)
- Don't use `Thread.sleep()` for async (use Awaitility)
- Don't forget to `reset()` mocks between tests
- Don't test private methods directly (test through public API)
- Don't skip cleanup in `@AfterEach`
- Don't hardcode timeouts (use Duration)
- Don't ignore test failures
- Don't write tests without assertions

## Additional Resources

- [Quarkus Testing Guide](https://quarkus.io/guides/getting-started-testing)
- [Mockito Documentation](https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html)
- [Awaitility Documentation](https://github.com/awaitility/awaitility)
- [RestAssured Documentation](https://rest-assured.io/)
- [TestContainers Documentation](https://www.testcontainers.org/)
- [JUnit 5 User Guide](https://junit.org/junit5/docs/current/user-guide/)

---

*For test coverage summary, see [TEST_SUMMARY.md](../TEST_SUMMARY.md)*
