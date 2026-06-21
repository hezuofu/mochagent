// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.concurrent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessageQueueTest {

    @Test void dequeuesHighestPriorityFirst() {
        var q = new MessageQueue<String>();
        q.enqueue("later", MessagePriority.LATER, "test");
        q.enqueue("now", MessagePriority.NOW, "test");
        q.enqueue("next", MessagePriority.NEXT, "test");

        assertEquals("now", q.dequeue().command());
        assertEquals("next", q.dequeue().command());
        assertEquals("later", q.dequeue().command());
        assertNull(q.dequeue());
    }

    @Test void fifoWithinSamePriority() {
        var q = new MessageQueue<String>();
        q.enqueue("a", MessagePriority.NEXT, "test");
        q.enqueue("b", MessagePriority.NEXT, "test");
        assertEquals("a", q.dequeue().command());
        assertEquals("b", q.dequeue().command());
    }

    @Test void enqueueLater() {
        var q = new MessageQueue<String>();
        q.enqueueLater("notification", "system");
        var e = q.dequeue();
        assertEquals(MessagePriority.LATER, e.priority());
        assertEquals("system", e.source());
    }

    @Test void filterDequeue() {
        var q = new MessageQueue<String>();
        q.enqueue("a", MessagePriority.NEXT, "cli");
        q.enqueue("b", MessagePriority.NEXT, "web");
        var e = q.dequeue(entry -> "web".equals(entry.source()));
        assertEquals("b", e.command());
        assertNull(q.dequeue(entry -> "web".equals(entry.source())));
    }
}
