# Domain-Driven Architecture with Java + Quarkus

## Overview

This document describes a pragmatic, type-safe architecture for building domain-driven applications using Java and Quarkus. The architecture emphasizes:

- **Transactional guarantees** with events committed atomically
- **Optimistic locking** for concurrency control
- **Merge-friendly structure** with one file per operation
- **Forward recovery** over compensation sagas
- **State-based persistence** with domain events as side effects
- **Compile-time enforcement** of event emission

## Core Principles

### 1. Events Must Be Committed Atomically with State Changes

The `UnitOfWork` class is the **only** way to successfully complete an operation. It ensures:
- Aggregate state is persisted
- Domain event is stored in outbox
- Optimistic locking is enforced
- Transaction is atomic

### 2. One Operation Per File

Each use case lives in its own folder:
```
domain/order/operations/
├── dispatchorder/
│   ├── DispatchOrderCommand.java
│   ├── DispatchOrderUseCase.java
│   └── DispatchOrderTest.java
├── cancelorder/
│   ├── CancelOrderCommand.java
│   ├── CancelOrderUseCase.java
│   └── CancelOrderTest.java
```

This prevents merge conflicts when multiple developers work on different operations.

### 3. Forward Recovery, Not Compensation

Operations don't try to "undo" previous work. Instead:
- Retry failed operations (idempotent)
- Move to explicit failure states
- Route to manual intervention
- Design for partial success

### 4. Package-Private Result Constructor

The `Result.success()` factory method is **package-private**, ensuring only `UnitOfWork` can create success results. This guarantees events are always emitted.

## Project Structure
```
src/main/java/
├── com.yourcompany.domain/
│   ├── common/
│   │   ├── Result.java                    # Result type
│   │   ├── DomainEvent.java               # Base event
│   │   ├── UnitOfWork.java                # Unit of work
│   │   ├── ExecutionContext.java          # Request context
│   │   └── errors/
│   │       ├── UseCaseError.java
│   │       ├── ValidationError.java
│   │       ├── BusinessRuleViolation.java
│   │       ├── NotFoundError.java
│   │       └── ConcurrencyError.java
│   ├── order/
│   │   ├── Order.java                     # Aggregate
│   │   ├── OrderRepository.java           # Repository interface
│   │   ├── OrderOperations.java           # Boundary class
│   │   ├── events/
│   │   │   ├── OrderDispatched.java
│   │   │   ├── OrderCancelled.java
│   │   │   └── ItemPacked.java
│   │   └── operations/
│   │       ├── dispatchorder/
│   │       │   ├── DispatchOrderCommand.java
│   │       │   ├── DispatchOrderUseCase.java
│   │       │   └── DispatchOrderTest.java
│   │       ├── cancelorder/
│   │       ├── shiporder/
│   │       └── packitem/
│   └── trip/
│       ├── Trip.java
│       ├── TripRepository.java
│       ├── TripOperations.java
│       └── operations/
│           ├── scheduletrip/
│           ├── assigndriver/
│           └── starttrip/
├── com.yourcompany.infrastructure/
│   ├── persistence/
│   │   ├── entities/
│   │   │   ├── OrderEntity.java
│   │   │   └── EventOutboxEntity.java
│   │   ├── repositories/
│   │   │   └── JpaOrderRepository.java
│   │   ├── EventOutbox.java
│   │   └── JpaUnitOfWork.java
│   └── config/
└── com.yourcompany.api/
    └── resources/
        └── OrderResource.java
```

## Core Components

### Result Type (Sealed Interface)
```java
// domain/common/Result.java
package com.yourcompany.domain.common;

public sealed interface Result<T> permits Result.Success, Result.Failure {
    
    boolean isSuccess();
    boolean isFailure();
    
    record Success<T>(T value) implements Result<T> {
        @Override
        public boolean isSuccess() { return true; }
        
        @Override
        public boolean isFailure() { return false; }
    }
    
    record Failure<T>(UseCaseError error) implements Result<T> {
        @Override
        public boolean isSuccess() { return false; }
        
        @Override
        public boolean isFailure() { return true; }
    }
    
    // Package-private - only UnitOfWork can call this!
    static <T> Result<T> success(T value) {
        return new Success<>(value);
    }
    
    // Public - anyone can create failures
    static <T> Result<T> failure(UseCaseError error) {
        return new Failure<>(error);
    }
}
```

### Error Hierarchy
```java
// domain/common/errors/UseCaseError.java
package com.yourcompany.domain.common.errors;

public sealed interface UseCaseError {
    String code();
    String message();
    Map<String, Object> details();
    
    record ValidationError(
        String code,
        String message,
        Map<String, Object> details
    ) implements UseCaseError {}
    
    record BusinessRuleViolation(
        String code,
        String message,
        Map<String, Object> details
    ) implements UseCaseError {}
    
    record NotFoundError(
        String code,
        String message,
        Map<String, Object> details
    ) implements UseCaseError {}
    
    record ConcurrencyError(
        String code,
        String message,
        Map<String, Object> details
    ) implements UseCaseError {}
}
```

### Domain Event Base
```java
// domain/common/DomainEvent.java
package com.yourcompany.domain.common;

import java.time.Instant;
import java.util.UUID;

public abstract class DomainEvent {
    private final String eventId;
    private final String eventType;
    private final String aggregateId;
    private final String aggregateType;
    private final String executionId;
    private final String correlationId;
    private final String causationId;
    private final Instant timestamp;
    private final long version;
    
    protected DomainEvent(Builder<?> builder) {
        this.eventId = builder.eventId != null ? builder.eventId : UUID.randomUUID().toString();
        this.eventType = builder.eventType;
        this.aggregateId = builder.aggregateId;
        this.aggregateType = builder.aggregateType;
        this.executionId = builder.executionId;
        this.correlationId = builder.correlationId;
        this.causationId = builder.causationId;
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
        this.version = builder.version;
    }
    
    // Getters...
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getAggregateId() { return aggregateId; }
    public String getAggregateType() { return aggregateType; }
    public String getExecutionId() { return executionId; }
    public String getCorrelationId() { return correlationId; }
    public String getCausationId() { return causationId; }
    public Instant getTimestamp() { return timestamp; }
    public long getVersion() { return version; }
    
    public abstract static class Builder<T extends Builder<T>> {
        private String eventId;
        private String eventType;
        private String aggregateId;
        private String aggregateType;
        private String executionId;
        private String correlationId;
        private String causationId;
        private Instant timestamp;
        private long version;
        
        protected abstract T self();
        
        public T eventType(String eventType) {
            this.eventType = eventType;
            return self();
        }
        
        public T aggregateId(String aggregateId) {
            this.aggregateId = aggregateId;
            return self();
        }
        
        public T aggregateType(String aggregateType) {
            this.aggregateType = aggregateType;
            return self();
        }
        
        public T executionId(String executionId) {
            this.executionId = executionId;
            return self();
        }
        
        public T correlationId(String correlationId) {
            this.correlationId = correlationId;
            return self();
        }
        
        public T causationId(String causationId) {
            this.causationId = causationId;
            return self();
        }
        
        public T version(long version) {
            this.version = version;
            return self();
        }
    }
}
```

### Execution Context
```java
// domain/common/ExecutionContext.java
package com.yourcompany.domain.common;

import java.time.Instant;
import java.util.UUID;

public record ExecutionContext(
    String executionId,
    String correlationId,
    String causationId,
    String userId,
    Instant initiatedAt
) {
    public static ExecutionContext create() {
        return new ExecutionContext(
            "execution." + UUID.randomUUID(),
            UUID.randomUUID().toString(),
            null,
            null,
            Instant.now()
        );
    }
    
    public static ExecutionContext fromParentEvent(DomainEvent parentEvent) {
        return new ExecutionContext(
            parentEvent.getExecutionId(),
            parentEvent.getCorrelationId(),
            parentEvent.getEventId(),
            null,
            Instant.now()
        );
    }
}
```

### Unit of Work
```java
// domain/common/UnitOfWork.java
package com.yourcompany.domain.common;

/**
 * Unit of Work manages atomic commits of aggregate + event.
 * This is the ONLY class that can create Success results.
 */
public interface UnitOfWork {
    /**
     * Commits aggregate state change along with domain event.
     * Returns Success - the only way to create one!
     */
    <T extends DomainEvent> Result<T> commit(Object aggregate, T event);
}
```
```java
// infrastructure/persistence/JpaUnitOfWork.java
package com.yourcompany.infrastructure.persistence;

import com.yourcompany.domain.common.DomainEvent;
import com.yourcompany.domain.common.Result;
import com.yourcompany.domain.common.UnitOfWork;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

@ApplicationScoped
public class JpaUnitOfWork implements UnitOfWork {
    
    @Inject
    EntityManager entityManager;
    
    @Inject
    EventOutbox eventOutbox;
    
    @Override
    public <T extends DomainEvent> Result<T> commit(Object aggregate, T event) {
        if (event == null) {
            throw new IllegalArgumentException("Domain event is required for commit");
        }
        
        // Increment version on aggregate
        if (aggregate.getClass().getMethod("getVersion") != null) {
            try {
                var versionField = aggregate.getClass().getDeclaredField("version");
                versionField.setAccessible(true);
                var currentVersion = (Long) versionField.get(aggregate);
                versionField.set(aggregate, currentVersion + 1);
            } catch (Exception e) {
                // Log warning but continue
            }
        }
        
        // Persist aggregate (JPA handles optimistic lock check)
        entityManager.merge(aggregate);
        
        // Store event in outbox (same transaction)
        eventOutbox.store(event);
        
        // Flush to trigger optimistic lock exception if version conflict
        entityManager.flush();
        
        // Return success - only UnitOfWork can do this!
        return Result.success(event);
    }
}
```

### Aggregate
```java
// domain/order/Order.java
package com.yourcompany.domain.order;

import jakarta.persistence.*;

@Entity
@Table(name = "orders")
public class Order {
    
    @Id
    private String id;
    
    @Version
    private Long version;
    
    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    
    private String tripId;
    
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderLine> orderLines = new ArrayList<>();
    
    protected Order() {} // JPA
    
    public Order(String id) {
        this.id = id;
        this.version = 1L;
        this.status = OrderStatus.NEW;
    }
    
    // Business methods
    public boolean canBeDispatched() {
        return status == OrderStatus.PACKED && tripId == null;
    }
    
    public void dispatch(String tripId) {
        if (!canBeDispatched()) {
            throw new IllegalStateException("Order cannot be dispatched in status " + status);
        }
        
        this.tripId = tripId;
        this.status = OrderStatus.DISPATCHED;
        // Version will be incremented by UnitOfWork
    }
    
    public boolean canBeCancelled() {
        return status != OrderStatus.DISPATCHED;
    }
    
    public void cancel() {
        if (!canBeCancelled()) {
            throw new IllegalStateException("Cannot cancel dispatched order");
        }
        
        this.status = OrderStatus.CANCELLED;
    }
    
    // Getters and setters...
    public String getId() { return id; }
    public Long getVersion() { return version; }
    public OrderStatus getStatus() { return status; }
    public String getTripId() { return tripId; }
    public List<OrderLine> getOrderLines() { return orderLines; }
}

enum OrderStatus {
    NEW, PACKED, DISPATCHED, CANCELLED
}
```

### Domain Events
```java
// domain/order/events/OrderDispatched.java
package com.yourcompany.domain.order.events;

import com.yourcompany.domain.common.DomainEvent;
import java.time.Instant;

public class OrderDispatched extends DomainEvent {
    private final String orderId;
    private final String tripId;
    private final Instant dispatchedAt;
    
    private OrderDispatched(Builder builder) {
        super(builder);
        this.orderId = builder.orderId;
        this.tripId = builder.tripId;
        this.dispatchedAt = builder.dispatchedAt;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public String getOrderId() { return orderId; }
    public String getTripId() { return tripId; }
    public Instant getDispatchedAt() { return dispatchedAt; }
    
    public static class Builder extends DomainEvent.Builder<Builder> {
        private String orderId;
        private String tripId;
        private Instant dispatchedAt;
        
        @Override
        protected Builder self() {
            return this;
        }
        
        public Builder orderId(String orderId) {
            this.orderId = orderId;
            return this;
        }
        
        public Builder tripId(String tripId) {
            this.tripId = tripId;
            return this;
        }
        
        public Builder dispatchedAt(Instant dispatchedAt) {
            this.dispatchedAt = dispatchedAt;
            return this;
        }
        
        public OrderDispatched build() {
            eventType("OrderDispatched");
            aggregateType("order");
            return new OrderDispatched(this);
        }
    }
}
```
```java
// domain/order/events/OrderCancelled.java
package com.yourcompany.domain.order.events;

import com.yourcompany.domain.common.DomainEvent;
import java.time.Instant;

public class OrderCancelled extends DomainEvent {
    private final String orderId;
    private final String reason;
    private final Instant cancelledAt;
    
    private OrderCancelled(Builder builder) {
        super(builder);
        this.orderId = builder.orderId;
        this.reason = builder.reason;
        this.cancelledAt = builder.cancelledAt;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public String getOrderId() { return orderId; }
    public String getReason() { return reason; }
    public Instant getCancelledAt() { return cancelledAt; }
    
    public static class Builder extends DomainEvent.Builder<Builder> {
        private String orderId;
        private String reason;
        private Instant cancelledAt;
        
        @Override
        protected Builder self() {
            return this;
        }
        
        public Builder orderId(String orderId) {
            this.orderId = orderId;
            return this;
        }
        
        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }
        
        public Builder cancelledAt(Instant cancelledAt) {
            this.cancelledAt = cancelledAt;
            return this;
        }
        
        public OrderCancelled build() {
            eventType("OrderCancelled");
            aggregateType("order");
            return new OrderCancelled(this);
        }
    }
}
```

### Repository Interface
```java
// domain/order/OrderRepository.java
package com.yourcompany.domain.order;

import java.util.Optional;

public interface OrderRepository {
    Optional<Order> findByIdForUpdate(String orderId);
    void save(Order order);
}
```

## Use Case Structure

### Command Definition
```java
// domain/order/operations/dispatchorder/DispatchOrderCommand.java
package com.yourcompany.domain.order.operations.dispatchorder;

public record DispatchOrderCommand(
    String orderId,
    String tripId
) {}
```

### Use Case Implementation
```java
// domain/order/operations/dispatchorder/DispatchOrderUseCase.java
package com.yourcompany.domain.order.operations.dispatchorder;

import com.yourcompany.domain.common.*;
import com.yourcompany.domain.common.errors.*;
import com.yourcompany.domain.order.*;
import com.yourcompany.domain.order.events.OrderDispatched;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Map;

@ApplicationScoped
public class DispatchOrderUseCase {
    
    @Inject
    OrderRepository orderRepository;
    
    @Inject
    UnitOfWork unitOfWork;
    
    @Transactional
    public Result<OrderDispatched> execute(
        DispatchOrderCommand command, 
        ExecutionContext context
    ) {
        // Validation
        if (command.tripId() == null || command.tripId().isBlank()) {
            return Result.failure(new UseCaseError.ValidationError(
                "TRIP_REQUIRED",
                "Trip ID is required for dispatch",
                Map.of("orderId", command.orderId())
            ));
        }
        
        // Load aggregate with optimistic lock
        Order order = orderRepository.findByIdForUpdate(command.orderId())
            .orElseThrow(() -> new NotFoundException(
                "ORDER_NOT_FOUND",
                "Order " + command.orderId() + " not found"
            ));
        
        // Business rule validation
        if (!order.canBeDispatched()) {
            return Result.failure(new UseCaseError.BusinessRuleViolation(
                "CANNOT_DISPATCH",
                "Order cannot be dispatched in current state",
                Map.of(
                    "orderId", order.getId(),
                    "currentStatus", order.getStatus(),
                    "requiredStatus", "PACKED"
                )
            ));
        }
        
        // Apply state change
        order.dispatch(command.tripId());
        
        // Create domain event
        OrderDispatched event = OrderDispatched.builder()
            .aggregateId("order." + order.getId())
            .executionId(context.executionId())
            .correlationId(context.correlationId())
            .causationId(context.causationId())
            .orderId(order.getId())
            .tripId(command.tripId())
            .dispatchedAt(Instant.now())
            .version(order.getVersion())
            .build();
        
        // Commit atomically (entity + event in outbox)
        return unitOfWork.commit(order, event);
    }
}
```

## Operations Boundary
```java
// domain/order/OrderOperations.java
package com.yourcompany.domain.order;

import com.yourcompany.domain.common.ExecutionContext;
import com.yourcompany.domain.common.Result;
import com.yourcompany.domain.order.events.*;
import com.yourcompany.domain.order.operations.dispatchorder.*;
import com.yourcompany.domain.order.operations.cancelorder.*;
import com.yourcompany.domain.order.operations.shiporder.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * OrderOperations - Single point of discovery for Order aggregate.
 * All operations on Orders go through this service.
 */
@ApplicationScoped
public class OrderOperations {
    
    @Inject
    DispatchOrderUseCase dispatchOrderUseCase;
    
    @Inject
    CancelOrderUseCase cancelOrderUseCase;
    
    @Inject
    ShipOrderUseCase shipOrderUseCase;
    
    public Result<OrderDispatched> dispatchOrder(
        DispatchOrderCommand command,
        ExecutionContext context
    ) {
        return dispatchOrderUseCase.execute(command, context);
    }
    
    public Result<OrderCancelled> cancelOrder(
        CancelOrderCommand command,
        ExecutionContext context
    ) {
        return cancelOrderUseCase.execute(command, context);
    }
    
    public Result<OrderShipped> shipOrder(
        ShipOrderCommand command,
        ExecutionContext context
    ) {
        return shipOrderUseCase.execute(command, context);
    }
}
```

## Infrastructure Layer

### JPA Entities
```java
// infrastructure/persistence/entities/OrderEntity.java
package com.yourcompany.infrastructure.persistence.entities;

import jakarta.persistence.*;

@Entity
@Table(name = "orders")
public class OrderEntity {
    
    @Id
    private String id;
    
    @Version  // Optimistic locking
    private Long version;
    
    @Enumerated(EnumType.STRING)
    private String status;
    
    private String tripId;
    
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    private List<OrderLineEntity> orderLines;
    
    // Getters and setters...
}
```
```java
// infrastructure/persistence/entities/EventOutboxEntity.java
package com.yourcompany.infrastructure.persistence.entities;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "event_outbox")
public class EventOutboxEntity {
    
    @Id
    private String id;
    
    @Column(nullable = false, unique = true)
    private String eventId;
    
    @Column(nullable = false)
    private String eventType;
    
    @Column(nullable = false)
    private String aggregateId;
    
    @Column(nullable = false)
    private String aggregateType;
    
    private String executionId;
    private String correlationId;
    
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;
    
    @Column(nullable = false)
    private Instant createdAt;
    
    @Column(nullable = false)
    private String status; // PENDING, PUBLISHED, FAILED
    
    private Instant publishedAt;
    private Integer retryCount;
    
    // Getters and setters...
}
```

### Repository Implementation
```java
// infrastructure/persistence/repositories/JpaOrderRepository.java
package com.yourcompany.infrastructure.persistence.repositories;

import com.yourcompany.domain.order.Order;
import com.yourcompany.domain.order.OrderRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;

@ApplicationScoped
public class JpaOrderRepository implements OrderRepository {
    
    @Inject
    EntityManager entityManager;
    
    @Override
    public Optional<Order> findByIdForUpdate(String orderId) {
        Order order = entityManager.find(
            Order.class, 
            orderId,
            LockModeType.OPTIMISTIC_FORCE_INCREMENT
        );
        return Optional.ofNullable(order);
    }
    
    @Override
    public void save(Order order) {
        if (order.getVersion() == null || order.getVersion() == 1L) {
            entityManager.persist(order);
        } else {
            entityManager.merge(order);
        }
    }
}
```

### Event Outbox
```java
// infrastructure/persistence/EventOutbox.java
package com.yourcompany.infrastructure.persistence;

import com.yourcompany.domain.common.DomainEvent;
import com.yourcompany.infrastructure.persistence.entities.EventOutboxEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class EventOutbox {
    
    @Inject
    EntityManager entityManager;
    
    @Inject
    ObjectMapper objectMapper;
    
    public void store(DomainEvent event) {
        try {
            EventOutboxEntity entity = new EventOutboxEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setEventId(event.getEventId());
            entity.setEventType(event.getEventType());
            entity.setAggregateId(event.getAggregateId());
            entity.setAggregateType(event.getAggregateType());
            entity.setExecutionId(event.getExecutionId());
            entity.setCorrelationId(event.getCorrelationId());
            entity.setPayload(objectMapper.writeValueAsString(event));
            entity.setCreatedAt(Instant.now());
            entity.setStatus("PENDING");
            entity.setRetryCount(0);
            
            entityManager.persist(entity);
        } catch (Exception e) {
            throw new RuntimeException("Failed to store event in outbox", e);
        }
    }
}
```

## API Layer

### REST Resource
```java
// api/resources/OrderResource.java
package com.yourcompany.api.resources;

import com.yourcompany.domain.common.ExecutionContext;
import com.yourcompany.domain.common.Result;
import com.yourcompany.domain.order.OrderOperations;
import com.yourcompany.domain.order.events.OrderDispatched;
import com.yourcompany.domain.order.operations.dispatchorder.DispatchOrderCommand;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@Path("/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderResource {
    
    @Inject
    OrderOperations orderOperations;
    
    @POST
    @Path("/{orderId}/dispatch")
    public Response dispatchOrder(
        @PathParam("orderId") String orderId,
        DispatchOrderRequest request
    ) {
        DispatchOrderCommand command = new DispatchOrderCommand(
            orderId,
            request.tripId()
        );
        
        ExecutionContext context = ExecutionContext.create();
        
        Result<OrderDispatched> result = orderOperations.dispatchOrder(command, context);
        
        return switch (result) {
            case Result.Success<OrderDispatched> s -> 
                Response.ok(Map.of(
                    "eventId", s.value().getEventId(),
                    "orderId", s.value().getOrderId(),
                    "status", "dispatched"
                )).build();
                
            case Result.Failure<OrderDispatched> f -> 
                Response.status(mapErrorToStatus(f.error()))
                    .entity(Map.of(
                        "code", f.error().code(),
                        "message", f.error().message(),
                        "details", f.error().details()
                    ))
                    .build();
        };
    }
    
    private Response.Status mapErrorToStatus(UseCaseError error) {
        return switch (error) {
            case UseCaseError.ValidationError v -> Response.Status.BAD_REQUEST;
            case UseCaseError.BusinessRuleViolation b -> Response.Status.CONFLICT;
            case UseCaseError.NotFoundError n -> Response.Status.NOT_FOUND;
            case UseCaseError.ConcurrencyError c -> Response.Status.CONFLICT;
        };
    }
    
    public record DispatchOrderRequest(String tripId) {}
}
```

## Testing
```java
// domain/order/operations/dispatchorder/DispatchOrderTest.java
package com.yourcompany.domain.order.operations.dispatchorder;

import com.yourcompany.domain.common.*;
import com.yourcompany.domain.order.*;
import com.yourcompany.domain.order.events.OrderDispatched;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class DispatchOrderUseCaseTest {
    
    @Inject
    DispatchOrderUseCase useCase;
    
    @Inject
    OrderRepository orderRepository;
    
    @Test
    @Transactional
    void shouldDispatchOrderSuccessfully() {
        // Given
        Order order = createPackedOrder();
        orderRepository.save(order);
        
        DispatchOrderCommand command = new DispatchOrderCommand(
            order.getId(),
            "trip-123"
        );
        ExecutionContext context = ExecutionContext.create();
        
        // When
        Result<OrderDispatched> result = useCase.execute(command, context);
        
        // Then
        assertThat(result.isSuccess()).isTrue();
        
        OrderDispatched event = ((Result.Success<OrderDispatched>) result).value();
        assertThat(event.getOrderId()).isEqualTo(order.getId());
        assertThat(event.getTripId()).isEqualTo("trip-123");
        assertThat(event.getAggregateId()).isEqualTo("order." + order.getId());
        assertThat(event.getExecutionId()).isEqualTo(context.executionId());
    }
    
    @Test
    @Transactional
    void shouldFailWhenOrderCannotBeDispatched() {
        // Given
        Order order = createNewOrder(); // Not yet packed
        orderRepository.save(order);
        
        DispatchOrderCommand command = new DispatchOrderCommand(
            order.getId(),
            "trip-123"
        );
        
        // When
        Result<OrderDispatched> result = useCase.execute(command, ExecutionContext.create());
        
        // Then
        assertThat(result.isFailure()).isTrue();
        
        UseCaseError error = ((Result.Failure<OrderDispatched>) result).error();
        assertThat(error).isInstanceOf(UseCaseError.BusinessRuleViolation.class);
        assertThat(error.code()).isEqualTo("CANNOT_DISPATCH");
    }
    
    private Order createPackedOrder() {
        Order order = new Order("order-" + UUID.randomUUID());
        // Set up order as PACKED
        return order;
    }
    
    private Order createNewOrder() {
        return new Order("order-" + UUID.randomUUID());
    }
}
```

## Event Naming Conventions

Events should be named after **what happened**, in past tense:

- ✅ `OrderDispatched` (not `DispatchOrder`)
- ✅ `TripScheduled` (not `ScheduleTrip`)
- ✅ `ItemPacked` (not `PackItem`)
- ✅ `DriverAssigned` (not `AssignDriver`)

Commands are imperatives, events are past tense:
- Command: `DispatchOrderCommand` → Event: `OrderDispatched`
- Command: `ScheduleTripCommand` → Event: `TripScheduled`

## Event ID Pattern

Events should include contextual IDs for traceability:
```java
OrderDispatched.builder()
    .eventId("uuid")                      // Unique event identifier
    .aggregateId("order." + orderId)      // The aggregate that changed
    .aggregateType("order")                // Type for routing
    .executionId("execution." + uuid)      // Process/saga identifier
    .correlationId("uuid")                 // Original request
    .causationId("parent-event-uuid")      // Parent event that caused this
    .version(5)                            // Aggregate version after change
    .build();
```

## Adding a New Use Case

1. **Create package structure:**
```
   domain/order/operations/refundorder/
   ├── RefundOrderCommand.java
   ├── RefundOrderUseCase.java
   └── RefundOrderTest.java
```

2. **Create command:**
```java
   public record RefundOrderCommand(
       String orderId,
       BigDecimal refundAmount,
       String reason
   ) {}
```

3. **Create use case:**
```java
   @ApplicationScoped
   public class RefundOrderUseCase {
       @Inject OrderRepository orderRepository;
       @Inject UnitOfWork unitOfWork;
       
       @Transactional
       public Result<OrderRefunded> execute(
           RefundOrderCommand command,
           ExecutionContext context
       ) {
           // Implementation
       }
   }
```

4. **Add to OrderOperations (one method):**
```java
   @ApplicationScoped
   public class OrderOperations {
       // ... existing use cases
       
       @Inject
       RefundOrderUseCase refundOrderUseCase;
       
       public Result<OrderRefunded> refundOrder(
           RefundOrderCommand command,
           ExecutionContext context
       ) {
           return refundOrderUseCase.execute(command, context);
       }
   }
```

5. **Add event:**
```java
   public class OrderRefunded extends DomainEvent {
       // Event definition
   }
```

## Best Practices

### 1. Keep Use Cases Small and Focused
Each use case should do **one thing**. If you need multiple events, you need multiple use cases.

### 2. Validate Early
Perform cheap validations before loading the aggregate.

### 3. Use Business Language
Names should reflect business concepts:
- ✅ `dispatchOrder`, `scheduleTrip`, `packItem`
- ❌ `updateOrderStatus`, `modifyTrip`, `changeItem`

### 4. Make Invariants Explicit
```java
if (!order.canBeDispatched()) {
    return Result.failure(new BusinessRuleViolation(...));
}
```

### 5. Event Versioning
When events change structure, create new event types:
- V1: `OrderDispatched`
- V2: `OrderDispatchedV2` or `OrderDispatchedWithTracking`

### 6. Idempotency
Design operations to be safely retryable:
```java
Order order = orderRepository.findByIdForUpdate(orderId);

// If already dispatched, return existing state
if (order.getStatus() == OrderStatus.DISPATCHED) {
    // Retrieve or reconstruct existing event
    return Result.success(existingEvent);
}
```

### 7. Testing Strategy
- **Unit tests**: Test use case logic with mocked repositories
- **Integration tests**: Test repository implementations with real database
- **End-to-end tests**: Test complete flows through API

### 8. Error Handling Hierarchy
```
Infrastructure errors (database down, network failure)
  → Throw exceptions
  → Should be retried automatically

Business rule violations (order cannot be dispatched)
  → Return Result.failure(BusinessRuleViolation)
  → Should be returned to user

Validation errors (missing required field)
  → Return Result.failure(ValidationError)
  → Should be returned to user immediately
```

## Common Patterns

### Pattern: Conditional State Transitions
```java
@Transactional
public Result<ItemPacked> execute(PackItemCommand command, ExecutionContext context) {
    Order order = orderRepository.findByIdForUpdate(command.orderId())
        .orElseThrow(() -> new NotFoundException(...));
    
    // Pack the item
    order.packItem(command.itemId());
    
    // Check if all items are now packed
    boolean allItemsPacked = order.allItemsArePacked();
    
    if (allItemsPacked) {
        order.setStatus(OrderStatus.PACKED);
    }
    
    ItemPacked event = ItemPacked.builder()
        .orderId(order.getId())
        .itemId(command.itemId())
        .allItemsPacked(allItemsPacked)
        .version(order.getVersion() + 1)
        .build();
    
    return unitOfWork.commit(order, event);
}
```

### Pattern: Loading Related Aggregates
```java
@Transactional
public Result<TripScheduled> execute(ScheduleTripCommand command, ExecutionContext context) {
    // Load order to verify it's ready for trip
    Order order = orderRepository.findByIdForUpdate(command.orderId())
        .orElseThrow(() -> new NotFoundException(...));
    
    if (order.getStatus() != OrderStatus.PACKED) {
        return Result.failure(new BusinessRuleViolation(...));
    }
    
    // Create new trip aggregate
    Trip trip = new Trip(
        UUID.randomUUID().toString(),
        order.getId(),
        command.route()
    );
    
    TripScheduled event = TripScheduled.builder()
        .tripId(trip.getId())
        .orderId(order.getId())
        .route(command.route())
        .version(trip.getVersion())
        .build();
    
    return unitOfWork.commit(trip, event);
}
```

## Key Guarantees

### 1. Package-Private Success Constructor
```java
// This won't compile - success() is package-private
return Result.success(event);  // ❌ Only works in same package

// Must go through UnitOfWork
return unitOfWork.commit(order, event);  // ✅ Only valid way
```

### 2. Atomic Transactions
```
BEGIN TRANSACTION
  1. Update order state (version check happens here)
  2. Insert event into outbox
  3. COMMIT
END TRANSACTION

If anything fails → entire transaction rolls back
```

### 3. Optimistic Locking
```java
// JPA's @Version automatically:
// - Reads current version when loading
// - Increments version when saving
// - Throws OptimisticLockException if version changed

aggregate.setVersion(aggregate.getVersion() + 1);  // 1 → 2
entityManager.merge(aggregate);  // UPDATE ... WHERE version = 1

// If another transaction changed it first, this fails
```

## FAQ

**Q: Why package-private Result.success()?**
A: It ensures only UnitOfWork can create successful results, guaranteeing events are always emitted when state changes.

**Q: Can I use this without Quarkus?**
A: Yes! The architecture works with any Java framework (Spring Boot, Micronaut, etc.). Just adapt the dependency injection.

**Q: How do I handle long-running processes?**
A: Use a saga/process manager that subscribes to events and issues commands. Keep sagas separate from use cases.

**Q: What about distributed transactions?**
A: This architecture uses eventual consistency. Each operation is atomic within one aggregate, but cross-aggregate operations are eventually consistent via events.

**Q: How do I version events?**
A: Create new event types (e.g., `OrderDispatchedV2`) rather than modifying existing ones. Event consumers handle both versions.

**Q: What about performance?**
A: The pattern adds minimal overhead. Optimistic locking scales better than pessimistic locking. Event outbox is efficient.

## References

- [Domain Events Pattern](https://martinfowler.com/eaaDev/DomainEvent.html)
- [Transactional Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html)
- [Quarkus Documentation](https://quarkus.io/guides/)
- [JPA Optimistic Locking](https://docs.oracle.com/javaee/7/tutorial/persistence-locking.htm)