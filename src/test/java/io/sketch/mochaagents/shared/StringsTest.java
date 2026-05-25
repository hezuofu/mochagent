// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.shared;

import org.junit.jupiter.api.Test;
import static io.sketch.mochaagents.shared.Strings.truncate;
import static org.junit.jupiter.api.Assertions.*;

class StringsTest {

    @Test void truncateShorterThanMax() {
        assertEquals("hello", truncate("hello", 10));
    }

    @Test void truncateLongerThanMax() {
        assertEquals("hello...", truncate("hello world", 5));
    }

    @Test void truncateExactLength() {
        assertEquals("hello", truncate("hello", 5));
    }

    @Test void truncateEmptyString() {
        assertEquals("", truncate("", 5));
    }

    @Test void truncateNullReturnsEmpty() {
        assertEquals("", truncate(null, 10));
    }
}
