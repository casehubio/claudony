package io.casehub.claudony.casehub.inbox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StallTrackerTest {

    StallTracker tracker = new StallTracker();

    @Test
    void initiallyEmpty() {
        assertTrue(tracker.stalledWorkerIds().isEmpty());
    }

    @Test
    void markStalled() {
        tracker.markStalled("worker-1");
        assertTrue(tracker.isStalled("worker-1"));
        assertEquals(1, tracker.stalledWorkerIds().size());
    }

    @Test
    void clearStall() {
        tracker.markStalled("worker-1");
        tracker.clearStall("worker-1");
        assertFalse(tracker.isStalled("worker-1"));
    }

    @Test
    void clearUnknownIsNoOp() {
        tracker.clearStall("unknown");
        assertTrue(tracker.stalledWorkerIds().isEmpty());
    }
}
