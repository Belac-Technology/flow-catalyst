package tech.flowcatalyst.streamprocessor.dispatch;

import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AggregateTracker.
 */
class AggregateTrackerTest {

    private AggregateTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new AggregateTracker("test-stream");
    }

    @Test
    void testIsInFlightBlocksDuplicates() {
        // Register batch 1 with aggregate "agg-1"
        Set<String> ids = new HashSet<>();
        ids.add("agg-1");
        tracker.registerBatch(1, ids);

        // "agg-1" should be in-flight
        assertTrue(tracker.isInFlight("agg-1"));

        // "agg-2" should not be in-flight
        assertFalse(tracker.isInFlight("agg-2"));
    }

    @Test
    void testCompleteBatchReleasesAggregates() {
        // Register batch 1 with aggregate "agg-1"
        Set<String> ids = new HashSet<>();
        ids.add("agg-1");
        tracker.registerBatch(1, ids);

        assertTrue(tracker.isInFlight("agg-1"));

        // Complete batch 1
        List<AggregateTracker.PendingDocument> released = tracker.completeBatch(1);

        // No pending documents, so none released
        assertTrue(released.isEmpty());

        // "agg-1" should no longer be in-flight
        assertFalse(tracker.isInFlight("agg-1"));
    }

    @Test
    void testPendingDocumentsReleasedCorrectly() {
        // Register batch 1 with aggregate "agg-1"
        Set<String> ids = new HashSet<>();
        ids.add("agg-1");
        tracker.registerBatch(1, ids);

        // Add pending document for "agg-1"
        Document doc = new Document("_id", "doc-1");
        tracker.addPending(new AggregateTracker.PendingDocument("agg-1", doc, null));

        assertEquals(1, tracker.getPendingCount());

        // Complete batch 1 - pending doc should be released
        List<AggregateTracker.PendingDocument> released = tracker.completeBatch(1);

        assertEquals(1, released.size());
        assertEquals("agg-1", released.get(0).aggregateId());
        assertEquals(0, tracker.getPendingCount());
    }

    @Test
    void testMultipleBatchesWithOverlappingAggregates() {
        // Register batch 1 with aggregate "agg-1"
        Set<String> ids1 = new HashSet<>();
        ids1.add("agg-1");
        tracker.registerBatch(1, ids1);

        // Register batch 2 with aggregate "agg-2"
        Set<String> ids2 = new HashSet<>();
        ids2.add("agg-2");
        tracker.registerBatch(2, ids2);

        // Both should be in-flight
        assertTrue(tracker.isInFlight("agg-1"));
        assertTrue(tracker.isInFlight("agg-2"));
        assertEquals(2, tracker.getInFlightBatchCount());

        // Add pending document for "agg-1"
        tracker.addPending(new AggregateTracker.PendingDocument(
                "agg-1", new Document("_id", "doc-1"), null));

        // Add pending document for "agg-2"
        tracker.addPending(new AggregateTracker.PendingDocument(
                "agg-2", new Document("_id", "doc-2"), null));

        assertEquals(2, tracker.getPendingCount());

        // Complete batch 1 - only "agg-1" pending doc should be released
        List<AggregateTracker.PendingDocument> released1 = tracker.completeBatch(1);
        assertEquals(1, released1.size());
        assertEquals("agg-1", released1.get(0).aggregateId());

        // "agg-2" doc is still pending
        assertEquals(1, tracker.getPendingCount());
        assertFalse(tracker.isInFlight("agg-1"));
        assertTrue(tracker.isInFlight("agg-2"));

        // Complete batch 2 - "agg-2" pending doc should be released
        List<AggregateTracker.PendingDocument> released2 = tracker.completeBatch(2);
        assertEquals(1, released2.size());
        assertEquals("agg-2", released2.get(0).aggregateId());

        assertEquals(0, tracker.getPendingCount());
        assertEquals(0, tracker.getInFlightBatchCount());
    }

    @Test
    void testNullAggregateIdNotTracked() {
        // null aggregate IDs should not be considered in-flight
        assertFalse(tracker.isInFlight(null));
    }

    @Test
    void testResetClearsState() {
        // Register batch with aggregate
        Set<String> ids = new HashSet<>();
        ids.add("agg-1");
        tracker.registerBatch(1, ids);

        // Add pending document
        tracker.addPending(new AggregateTracker.PendingDocument(
                "agg-1", new Document("_id", "doc-1"), null));

        // Verify state exists
        assertTrue(tracker.isInFlight("agg-1"));
        assertEquals(1, tracker.getPendingCount());

        // Reset
        tracker.reset();

        // State should be cleared
        assertFalse(tracker.isInFlight("agg-1"));
        assertEquals(0, tracker.getPendingCount());
        assertEquals(0, tracker.getInFlightBatchCount());
    }

    @Test
    void testPendingDocumentWithResumeToken() {
        // Test that resume token is preserved in pending document
        Set<String> ids = new HashSet<>();
        ids.add("agg-1");
        tracker.registerBatch(1, ids);

        BsonDocument token = BsonDocument.parse("{'_data': 'test-token'}");
        Document doc = new Document("_id", "doc-1");

        tracker.addPending(new AggregateTracker.PendingDocument("agg-1", doc, token));

        List<AggregateTracker.PendingDocument> released = tracker.completeBatch(1);

        assertEquals(1, released.size());
        assertNotNull(released.get(0).resumeToken());
        assertEquals(token, released.get(0).resumeToken());
    }

    @Test
    void testMultiplePendingForSameAggregate() {
        // Register batch with aggregate
        Set<String> ids = new HashSet<>();
        ids.add("agg-1");
        tracker.registerBatch(1, ids);

        // Add multiple pending documents for same aggregate
        tracker.addPending(new AggregateTracker.PendingDocument(
                "agg-1", new Document("_id", "doc-1"), null));
        tracker.addPending(new AggregateTracker.PendingDocument(
                "agg-1", new Document("_id", "doc-2"), null));
        tracker.addPending(new AggregateTracker.PendingDocument(
                "agg-1", new Document("_id", "doc-3"), null));

        assertEquals(3, tracker.getPendingCount());

        // Complete batch - all 3 should be released
        List<AggregateTracker.PendingDocument> released = tracker.completeBatch(1);

        assertEquals(3, released.size());
        assertEquals(0, tracker.getPendingCount());
    }

    @Test
    void testCompleteNonExistentBatch() {
        // Completing a batch that wasn't registered should return empty list
        List<AggregateTracker.PendingDocument> released = tracker.completeBatch(999);
        assertTrue(released.isEmpty());
    }
}
