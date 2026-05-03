package io.sketch.mochaagents.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PromptTemplateEdgeTest {

    // --- Edge cases ---

    @Test
    void nullValuesInRender() {
        PromptTemplate t = PromptTemplate.of("{key}");
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("key", null);
        String result = t.render(map);
        assertNotNull(result);
    }

    @Test
    void specialCharactersInTemplate() {
        PromptTemplate t = PromptTemplate.of("Regex: \\d+ and {name}");
        String result = t.render("name", "test");
        assertTrue(result.contains("Regex"));
    }

    @Test
    void veryLongTemplate() {
        String longTemplate = "a".repeat(10000) + "{x}";
        PromptTemplate t = PromptTemplate.of(longTemplate);
        String result = t.render("x", "y");
        assertTrue(result.length() > 10000);
    }

    @Test
    void curlyBracesWithoutPlaceholder() {
        PromptTemplate t = PromptTemplate.of("Just {braces} and more {stuff}");
        String result = t.render(Map.of());
        assertEquals("Just  and more ", result);
    }

    @Test
    void emptyValuesMap() {
        PromptTemplate t = PromptTemplate.of("Hello, {name}!");
        assertEquals("Hello, !", t.render(Map.of()));
    }

    // --- Failure cases ---

    @Test
    void doubleClosingBraces() {
        PromptTemplate t = PromptTemplate.of("{{key}}");
        String result = t.render("key", "val");
        // Should not throw - just doesn't match the placeholder pattern
        assertNotNull(result);
    }

    @Test
    void nestedBraces() {
        PromptTemplate t = PromptTemplate.of("{outer{inner}}");
        String result = t.render(Map.of());
        assertNotNull(result);
    }

    @Test
    void numericKeyPlaceholder() {
        PromptTemplate t = PromptTemplate.of("{123}");
        String result = t.render("123", "val");
        assertEquals("val", result);
    }
}
