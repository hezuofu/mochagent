// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool.internal;

import org.junit.jupiter.api.Test;
import java.util.Map;
import io.sketch.mochaagents.tool.Tool;
import static org.junit.jupiter.api.Assertions.*;

class CalculatorToolTest {

    private final CalculatorTool calc = new CalculatorTool();

    @Test void add() { assertTrue(calc.call(Map.of("expression", "2+3")).toString().contains("5")); }
    @Test void multiply() { assertTrue(calc.call(Map.of("expression", "4*5")).toString().contains("20")); }
    @Test void subtract() { assertTrue(calc.call(Map.of("expression", "10-3")).toString().contains("7")); }
    @Test void empty() { assertTrue(calc.call(Map.of("expression", "")).toString().contains("Error")); }
    @Test void securityLevel() { assertEquals(Tool.SecurityLevel.LOW, calc.getSecurityLevel()); }
    @Test void name() { assertEquals("calculator", calc.getName()); }
}
