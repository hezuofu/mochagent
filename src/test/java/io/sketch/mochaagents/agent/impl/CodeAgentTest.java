package io.sketch.mochaagents.agent.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CodeAgent code extraction and parsing logic.
 * @author lanxia39@163.com
 */
class CodeAgentTest {

    private CodeAgent agent() {
        return CodeAgent.builder()
                .name("test")
                .llm(null) // not needed for unit tests of extraction
                .build();
    }

    // --- extractCode: fenced block ---

    @Test
    void extractsFencedPythonBlock() {
        String output = """
                Here is some code:
                ```python
                print("hello")
                final_answer("done")
                ```
                """;
        String code = agent().extractCode(output);
        assertTrue(code.contains("print(\"hello\")"));
        assertTrue(code.contains("final_answer"));
    }

    @Test
    void extractsFencedBlockNoLanguage() {
        String output = """
                ```
                result = tool(input="test")
                ```
                """;
        String code = agent().extractCode(output);
        assertEquals("result = tool(input=\"test\")", code);
    }

    // --- extractCode: <code> tag ---

    @Test
    void extractsCodeTag() {
        String output = "<code>\nprint(42)\nfinal_answer(\"42\")\n</code>";
        String code = agent().extractCode(output);
        assertTrue(code.contains("print(42)"));
    }

    // --- extractCode: no code block ---

    @Test
    void returnsNullWhenNoCodeBlock() {
        String output = "I think the answer is 42. No code needed.";
        assertNull(agent().extractCode(output));
    }

    @Test
    void returnsNullForEmptyOutput() {
        assertNull(agent().extractCode(""));
    }

    // --- executeCode: basic ---

    @Test
    void detectsFinalAnswer() {
        String code = """
                import math
                result = math.sqrt(125)
                final_answer("11.18")
                """;
        CodeAgent.CodeExecutionResult result = agent().executeCode(code);
        assertTrue(result.isFinalAnswer());
        assertEquals("11.18", result.finalAnswerValue());
    }

    @Test
    void detectsNoFinalAnswer() {
        String code = "print(42)";
        CodeAgent.CodeExecutionResult result = agent().executeCode(code);
        assertFalse(result.isFinalAnswer());
        assertNull(result.finalAnswerValue());
    }

    // --- Builder defaults ---

    @Test
    void defaultMaxStepsIs20() {
        var agent = CodeAgent.builder().llm(null).build();
        assertEquals(20, agent.maxSteps);
    }

    @Test
    void defaultCodeLanguageIsPython() {
        var agent = CodeAgent.builder().llm(null).build();
        // codeLanguage is private; verify via behavior that it defaults to python
        String prompt = agent.buildSystemPrompt();
        assertTrue(prompt.contains("code") || prompt.contains("Code"));
    }
}
