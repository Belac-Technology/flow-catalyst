# Test Output Guide

## Overview

This guide shows you how to get beautiful, detailed test output showing all test names, descriptions, and assertions.

## Quick Start

```bash
# Option 1: Enhanced console output (already configured)
./gradlew clean test

# Option 2: Verbose with all details
./gradlew test --info

# Option 3: With full stack traces
./gradlew test --stacktrace

# Option 4: HTML report (prettiest!)
./gradlew test
open build/reports/tests/test/index.html
```

---

## Method 1: Enhanced Console Output ✅ (Configured)

The `build.gradle` is now configured to show detailed test output in the console.

### Run Tests
```bash
./gradlew clean test
```

### What You'll See
```
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running tech.flowcatalyst.messagerouter.manager.QueueManagerTest
  ✔ shouldRouteMessageToCorrectPool
  ✔ shouldDetectDuplicateMessages
  ✔ shouldHandlePoolNotFound
  ✔ shouldHandleQueueFull
  ✔ shouldDelegateAckToCallback
  ✔ shouldDelegateNackToCallback
  ✔ shouldHandleAckWithoutCallback
  ✔ shouldHandleNackWithoutCallback
  ✔ shouldRouteMultipleMessagesToDifferentPools
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.345 s
```

---

## Method 2: Pretty Console Output with Colors

Add this to your command for colorized output:

```bash
# With color and detailed info
./gradlew test --console=rich --info

# Show test hierarchy
./gradlew test -Djunit.platform.output.capture.stdout=true \
            -Djunit.platform.output.capture.stderr=true
```

---

## Method 3: HTML Report (Most Visual) 🎨

### Generate HTML Report
```bash
# Run tests and generate HTML report
./gradlew clean test

# Open the report
open build/reports/tests/test/index.html
# OR on Linux: xdg-open build/reports/tests/test/index.html
# OR on Windows: start build/reports/tests/test/index.html
```

### What You Get
- **Beautiful HTML page** with:
  - Summary statistics
  - Success/failure rates
  - Test execution times
  - Each test class expandable
  - All test methods listed
  - Stack traces for failures
  - Test duration graphs

### Sample Report View
```
FlowCatalyst Test Report
═══════════════════════════════════════

Summary
  Tests: 80    Success: 80    Failures: 0    Errors: 0    Skipped: 0
  Success Rate: 100%
  Total Time: 12.456s

QueueManagerTest                              [10 tests] ✅
  ├─ shouldRouteMessageToCorrectPool          0.234s ✅
  ├─ shouldDetectDuplicateMessages            0.156s ✅
  ├─ shouldHandlePoolNotFound                 0.089s ✅
  └─ ...

HttpMediatorTest                              [11 tests] ✅
  ├─ shouldReturnSuccessFor200Response        0.123s ✅
  ├─ shouldReturnErrorProcessFor400Response   0.145s ✅
  └─ ...
```

---

## Method 4: Tree-Style Output (CLI)

### Install JUnit Console Launcher
Add to `build.gradle` (optional):
```gradle
testImplementation 'org.junit.platform:junit-platform-console-standalone:1.11.4'
```

### Run with Tree Output
```bash
./gradlew test -Djunit.jupiter.testinstance.lifecycle.default=per_class
```

### Output Style
```
.
├─ QueueManagerTest ✔
│  ├─ shouldRouteMessageToCorrectPool() ✔
│  ├─ shouldDetectDuplicateMessages() ✔
│  ├─ shouldHandlePoolNotFound() ✔
│  ├─ shouldHandleQueueFull() ✔
│  ├─ shouldDelegateAckToCallback() ✔
│  ├─ shouldDelegateNackToCallback() ✔
│  ├─ shouldHandleAckWithoutCallback() ✔
│  ├─ shouldHandleNackWithoutCallback() ✔
│  └─ shouldRouteMultipleMessagesToDifferentPools() ✔
├─ HttpMediatorTest ✔
│  ├─ shouldReturnSuccessFor200Response() ✔
│  ├─ shouldReturnErrorProcessFor400Response() ✔
│  └─ ...
```

---

## Method 5: Verbose Output with Assertions

To see assertion details and stack traces:

```bash
# Show all output (including System.out from tests)
./gradlew test --info

# Show detailed failures
./gradlew test --stacktrace

# Show everything (very verbose)
./gradlew test --debug
```

### Example Output
```
shouldCalculateQueueStats():
  Given: QueueId = "test-queue"
         Messages received: 5
         Messages consumed: 3
         Messages failed: 1

  When: Getting queue stats

  Then: Assertions:
    ✓ totalMessages = 5
    ✓ totalConsumed = 3
    ✓ totalFailed = 1
    ✓ successRate = 0.6
    ✓ pendingMessages = 50
    ✓ messagesNotVisible = 15
    ✓ throughput >= 0
```

---

## Method 6: IDE Test Runners (Best Developer Experience) 💻

### IntelliJ IDEA
1. Right-click on `src/test/java`
2. Select "Run 'All Tests'" or press `Ctrl+Shift+F10`
3. View in Test Runner panel:
   - **Hierarchical view** of all tests
   - **Green/Red indicators** for pass/fail
   - **Click to see assertions**
   - **Re-run failed tests only**
   - **Debug any test**

### VS Code
1. Install "Test Runner for Java" extension
2. Open Testing panel (`Ctrl+Shift+T`)
3. Click "Run All Tests"
4. View results in tree view

### Eclipse
1. Right-click project → "Run As" → "JUnit Test"
2. View in JUnit view panel

---

## Method 7: Custom Test Reporter

For ultimate control, add a custom test execution listener.

### Create Custom Listener
```java
package tech.flowcatalyst.messagerouter.test;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

public class PrettyTestReporter implements TestWatcher {

    @Override
    public void testSuccessful(ExtensionContext context) {
        System.out.println("✅ " + context.getDisplayName() + " - PASSED");
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        System.out.println("❌ " + context.getDisplayName() + " - FAILED");
        System.out.println("   Reason: " + cause.getMessage());
    }

    @Override
    public void testDisabled(ExtensionContext context, java.util.Optional<String> reason) {
        System.out.println("⏭️  " + context.getDisplayName() + " - SKIPPED");
    }
}
```

### Use in Tests
```java
@QuarkusTest
@ExtendWith(PrettyTestReporter.class)
class MyTest {
    // tests...
}
```

---

## Method 8: Live Test Results (Watch Mode)

### Using Gradle with Continuous Testing
```bash
# Run tests continuously (re-run on file change)
./gradlew quarkusTest --continuous

# Opens interactive test mode:
# - Press 'r' to re-run tests
# - Press 'o' to run only failed tests
# - Press 'f' to run specific test
# - Press 'q' to quit
```

### Output
```
╔═══════════════════════════════════════════════════════════╗
║               Quarkus Continuous Testing                  ║
╠═══════════════════════════════════════════════════════════╣
║  Tests: 80    Passed: 80    Failed: 0    Skipped: 0      ║
║  Success Rate: 100%                                       ║
║  Last run: 2.345s ago                                     ║
╚═══════════════════════════════════════════════════════════╝

Press [r] to re-run, [o] to toggle test output, [h] for help
```

---

## Recommended Workflow

### For Daily Development
```bash
# Quick feedback with enhanced console
./gradlew test --tests MyNewTest

# Or use IDE test runner for instant feedback
```

### Before Committing
```bash
# Run all tests with full output
./gradlew clean test

# Generate HTML report to review
./gradlew test
open build/reports/tests/test/index.html
```

### In CI/CD Pipeline
```bash
# Run tests with detailed reporting
./gradlew clean test jacocoTestReport

# Archive reports
# - build/test-results/test/*.xml (JUnit format)
# - build/reports/tests/test/index.html (HTML)
# - build/reports/jacoco/ (Coverage)
```

---

## Test Output Files

After running tests, you'll find:

```
build/
├── test-results/test/              # Test results
│   └── TEST-*.xml                  # JUnit XML format (CI/CD friendly)
├── reports/
│   ├── tests/test/                 # HTML test report
│   │   └── index.html
│   └── jacoco/test/html/           # Code coverage report
│       └── index.html
└── classes/java/test/              # Compiled test classes
```

---

## Filtering Test Output

### Run Specific Test Class
```bash
./gradlew test --tests QueueManagerTest
```

### Run Specific Test Method
```bash
./gradlew test --tests QueueManagerTest.shouldRouteMessageToCorrectPool
```

### Run Tests Matching Pattern
```bash
# All tests with "Queue" in name
./gradlew test --tests '*Queue*'

# All consumer tests
./gradlew test --tests '*Consumer*Test'

# All integration tests
./gradlew test --tests '*IntegrationTest'
```

### Exclude Tests
```bash
# Skip integration tests
./gradlew test --exclude-task integrationTest

# Skip slow tests
./gradlew test --tests '!*SlowTest'
```

---

## Prettifying Output Further

### Add Color Output (Unix/Mac/Linux)
```bash
# Install unbuffer if needed
# brew install expect   (Mac)
# apt-get install expect (Ubuntu)

# Run with color
unbuffer ./gradlew test | more
```

### Custom Gradle Wrapper Script
Create `test-pretty.sh`:
```bash
#!/bin/bash
./gradlew clean test --console=rich --info \
                 2>&1 | grep -E "(Running|Tests run|PASSED|FAILED|✔|✗)" --color=always
```

Make executable:
```bash
chmod +x test-pretty.sh
./test-pretty.sh
```

---

## Summary

| Method | Best For | Output Quality | Setup |
|--------|----------|----------------|-------|
| Enhanced Console | Quick terminal runs | ⭐⭐⭐ | ✅ Done |
| HTML Report | Detailed review | ⭐⭐⭐⭐⭐ | Easy |
| IDE Runner | Development | ⭐⭐⭐⭐⭐ | None |
| Continuous Testing | TDD workflow | ⭐⭐⭐⭐ | Easy |
| Custom Reporter | Special needs | ⭐⭐⭐ | Medium |

### Our Recommendation
1. **For Development**: Use IDE test runner (IntelliJ/VS Code)
2. **For Terminal**: Use `./gradlew test` (already enhanced)
3. **For Review**: Generate HTML report with `./gradlew test`
4. **For CI/CD**: Use default Gradle output + archive XML reports

---

## Try It Now!

```bash
# 1. Run tests with enhanced console output
./gradlew clean test

# 2. Generate pretty HTML report
./gradlew test

# 3. Open the report
open build/reports/tests/test/index.html
```

You should now see beautiful, detailed test output! 🎉
