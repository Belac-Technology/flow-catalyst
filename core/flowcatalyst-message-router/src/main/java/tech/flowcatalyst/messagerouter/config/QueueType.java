package tech.flowcatalyst.messagerouter.config;

public enum QueueType {
    ACTIVEMQ,
    SQS,
    EMBEDDED  // Embedded SQLite queue for developer builds (replaces Chronicle)
}