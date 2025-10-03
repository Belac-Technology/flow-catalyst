# Flow Catalyst

Flow Catalyst is a high-performance message routing and webhook dispatch platform built with Quarkus and Java 21 virtual threads.

## Key Features

- **Message Router** - Process messages from SQS/ActiveMQ with concurrency control and rate limiting
- **Dispatch Jobs** - Reliable webhook delivery with HMAC signing, retries, and full audit trail
- **Virtual Threads** - Efficient I/O handling using Java 21 virtual threads
- **Real-time Monitoring** - Dashboard for queue stats, pool metrics, and health status

## Documentation

- **[Architecture](docs/architecture.md)** - System architecture and components
- **[Database Strategy](docs/database-strategy.md)** - Database design decisions and future plans
- **[Dispatch Jobs](docs/dispatch-jobs.md)** - Webhook delivery system details
- **[Testing Guide](docs/TESTING.md)** - Integration and unit testing

## Quick Start

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./gradlew quarkusDev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## Packaging and running the application

The application can be packaged using:

```shell script
./gradlew build
```

It produces the `quarkus-run.jar` file in the `build/quarkus-app/` directory.
Be aware that it's not an _über-jar_ as the dependencies are copied into the `build/quarkus-app/lib/` directory.

The application is now runnable using `java -jar build/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./gradlew build -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar build/*-runner.jar`.

## Creating a native executable

You can create a native executable using:

```shell script
./gradlew build -Dquarkus.native.enabled=true
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./gradlew build -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./build/flowatalyst-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/gradle-tooling>.

## Running Tests

### Quick Start

```shell script
# Run all tests
./gradlew clean test

# Run only unit tests (fast)
./gradlew test --tests '*' --exclude-task integrationTest

# Run only integration tests
./gradlew integrationTest
```

### Specific Tests

```shell script
# Run a specific test class
./gradlew test --tests QueueManagerTest

# Run a specific test method
./gradlew test --tests ProcessPoolImplTest.shouldEnforceRateLimit

# Run tests with verbose output
./gradlew test --debug
```

### Test Suite Overview

**Total Tests:** 80+
**Test Classes:** 10
**Status:** ✅ All passing

The comprehensive test suite includes:
- **Unit Tests** - Component testing with mocks
- **Integration Tests** - Real SQS (LocalStack), rate limiting, etc.
- **REST Endpoint Tests** - All monitoring APIs
- **Metrics Tests** - Queue and pool metrics validation

For detailed testing documentation, see:
- [Test Suite Summary](TEST_SUMMARY.md) - Overview of all tests
- [Testing Guide](docs/TESTING.md) - Comprehensive testing guide

## Related Guides

- REST ([guide](https://quarkus.io/guides/rest)): A Jakarta REST implementation utilizing build time processing and Vert.x. This extension is not compatible with the quarkus-resteasy extension, or any of the extensions that depend on it.
- REST Jackson ([guide](https://quarkus.io/guides/rest#json-serialisation)): Jackson serialization support for Quarkus REST. This extension is not compatible with the quarkus-resteasy extension, or any of the extensions that depend on it
