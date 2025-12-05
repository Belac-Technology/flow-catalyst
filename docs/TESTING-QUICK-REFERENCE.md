# Testing Quick Reference

## Quick Commands

```bash
# Run all tests
./gradlew clean test

# Run unit tests only (fast)
./gradlew test --tests '*' --exclude-task integrationTest

# Run integration tests only
./gradlew integrationTest

# Run specific test
./gradlew test --tests QueueManagerTest

# Run with debug logging
./gradlew test --debug

# Generate coverage report
./gradlew clean test jacocoTestReport
```

## Test Template

```java
@QuarkusTest
class MyComponentTest {

    @Inject
    MyComponent component;

    @InjectMock
    MyDependency mockDependency;

    @BeforeEach
    void setUp() {
        reset(mockDependency);
        when(mockDependency.method()).thenReturn(value);
    }

    @Test
    void shouldDoSomethingWhenConditionMet() {
        // Given
        var input = setupTestData();

        // When
        var result = component.process(input);

        // Then
        assertNotNull(result);
        verify(mockDependency).method();
    }
}
```

## Common Patterns

### Quarkus Test
```java
@QuarkusTest            // Enable Quarkus test support
@Inject                 // Inject real CDI beans
@InjectMock             // Inject mock CDI beans
```

### Mockito
```java
// Create mock
mock(SqsClient.class)

// Stub method
when(mock.method()).thenReturn(value)
when(mock.method()).thenThrow(exception)

// Verify calls
verify(mock).method()
verify(mock, times(2)).method()
verify(mock, never()).method()

// Capture arguments
ArgumentCaptor<Type> captor = ArgumentCaptor.forClass(Type.class);
verify(mock).method(captor.capture());
Type captured = captor.getValue();
```

### Awaitility
```java
// Wait for condition
await().untilAsserted(() -> {
    verify(mock).method();
    assertTrue(condition);
});

// With timeout
await()
    .atMost(Duration.ofSeconds(5))
    .untilAsserted(() -> ...);

// Wait for value
await().until(() -> list.size() == 5);
```

### RestAssured
```java
// GET request
given()
    .when().get("/api/endpoint")
    .then()
    .statusCode(200)
    .body("field", equalTo("value"));

// POST request
given()
    .contentType("application/json")
    .body(requestBody)
    .when().post("/api/endpoint")
    .then()
    .statusCode(201);

// Query parameters
given()
    .queryParam("hours", 24)
    .when().delete("/api/endpoint")
    .then().statusCode(200);
```

### Assertions
```java
// JUnit 5
assertEquals(expected, actual)
assertNotNull(value)
assertTrue(condition)
assertThrows(Exception.class, () -> code())

// Hamcrest (RestAssured)
equalTo(value)
notNullValue()
containsString("text")
hasSize(5)
```

## Test Naming

```java
// Pattern: should + ExpectedBehavior + When + Condition
shouldRouteMessageToCorrectPool()
shouldReturnErrorWhenPoolNotFound()
shouldHandleInvalidJson()
shouldAcknowledgeMessageOnSuccess()
```

## Common Mistakes

### ❌ Don't
```java
@Mock                   // Use @InjectMock with Quarkus
Thread.sleep(1000)     // Use Awaitility instead
@Test testMethod()      // Missing void return type
verify(mock, never())   // Wrong order - verify first
```

### ✅ Do
```java
@InjectMock            // Correct for Quarkus CDI
await().untilAsserted() // Async testing
@Test void testMethod() // Correct signature
reset(mock)            // Clean between tests
```

## Test Structure

```java
@Test
void shouldDoSomething() {
    // Given - Setup test scenario
    var input = createTestData();
    when(mock.method()).thenReturn(value);

    // When - Execute behavior under test
    var result = component.process(input);

    // Then - Assert expected outcomes
    assertNotNull(result);
    assertEquals(expected, result);
    verify(mock).method();
}
```

## Debugging

```bash
# Remote debug (attach on port 5005)
./gradlew test --debug-jvm

# Debug specific test
./gradlew test --tests MyTest --debug-jvm

# Enable Quarkus debug logging
./gradlew test -Dquarkus.log.level=DEBUG

# Debug specific package
./gradlew test -Dquarkus.log.category.\"tech.flowcatalyst\".level=DEBUG
```

## TestContainers

```java
@Container
static LocalStackContainer localstack = new LocalStackContainer(
    DockerImageName.parse("localstack/localstack:3.0")
).withServices(LocalStackContainer.Service.SQS);

@BeforeAll
static void setUp() {
    // Configure clients to use container
    SqsClient client = SqsClient.builder()
        .endpointOverride(localstack.getEndpoint())
        .build();
}
```

## Coverage

```bash
# Generate JaCoCo report
./gradlew clean test jacocoTestReport

# View report
open build/reports/jacoco/test/html/index.html
```

## When Tests Fail

1. **Check error message** - What assertion failed?
2. **Check logs** - Run with `-X` for verbose output
3. **Isolate test** - Run only failing test
4. **Check mocks** - Are they configured correctly?
5. **Check setup/cleanup** - `@BeforeEach` / `@AfterEach`
6. **Check Docker** - For TestContainers tests
7. **Check timing** - For async tests

## Test Files by Component

```
QueueManagerTest             - Message routing
HttpMediatorTest            - HTTP mediation
ActiveMqQueueConsumerTest   - ActiveMQ consumer
MicrometerQueueMetricsServiceTest - Metrics
MonitoringResourceTest      - REST endpoints
ProcessPoolImplTest         - Pool execution
SqsQueueConsumerTest        - SQS consumer
RateLimiterIntegrationTest  - Rate limiting
SqsLocalStackIntegrationTest - Real SQS
EndToEndIntegrationTest     - Full flow
```

## More Information

- **Test Output Guide**: [docs/TEST-OUTPUT-GUIDE.md](TEST-OUTPUT-GUIDE.md) - 🎨 How to get beautiful test output
- **Detailed guide**: [docs/TESTING.md](TESTING.md)
- **Coverage summary**: [TEST_SUMMARY.md](../TEST_SUMMARY.md)
- **Quarkus Testing**: https://quarkus.io/guides/getting-started-testing
