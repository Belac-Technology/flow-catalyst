package tech.flowcatalyst.postbox.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import tech.flowcatalyst.postbox.model.MessageStatus;
import tech.flowcatalyst.postbox.model.MessageType;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "postbox_messages")
public class PostboxMessage extends PanacheEntityBase {

    @Id
    @Column(columnDefinition = "TEXT")
    public String id;

    @Column(nullable = false)
    public Long tenantId;

    @Column(nullable = false)
    public String partitionId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    public MessageType type;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String payload;

    @Column(nullable = false)
    public Long payloadSize;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    public MessageStatus status = MessageStatus.PENDING;

    @Column(nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    @Column
    public Instant processedAt;

    @Column
    public Integer retryCount = 0;

    @Column(columnDefinition = "TEXT")
    public String errorReason;

    @Column(columnDefinition = "jsonb")
    public Map<String, Object> headers;

}
