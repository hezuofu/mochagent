package io.sketch.mochaagents.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PromptTemplate rendering.
 * @author lanxia39@163.com
 */
class PromptTemplateTest {

    @Test
    void rendersSinglePlaceholder() {
        PromptTemplate t = PromptTemplate.of("Hello, {name}!");
        assertEquals("Hello, World!", t.render("name", "World"));
    }

    @Test
    void rendersMultiplePlaceholders() {
        PromptTemplate t = PromptTemplate.of("Tool: {name}\nDesc: {desc}");
        assertEquals("Tool: search\nDesc: finds files", t.render(Map.of("name", "search", "desc", "finds files")));
    }

    @Test
    void missingKeyIsReplacedWithEmpty() {
        PromptTemplate t = PromptTemplate.of("Hello, {name}!");
        assertEquals("Hello, !", t.render("other", "ignored"));
    }

    @Test
    void noPlaceholdersReturnsTemplateAsIs() {
        PromptTemplate t = PromptTemplate.of("static text");
        assertEquals("static text", t.render(Map.of()));
    }

    @Test
    void emptyTemplateReturnsEmpty() {
        PromptTemplate t = PromptTemplate.of("");
        assertEquals("", t.render(Map.of("x", "y")));
    }

    @Test
    void templateMethodReturnsRawTemplate() {
        PromptTemplate t = PromptTemplate.of("{greeting}, {name}!");
        assertEquals("{greeting}, {name}!", t.template());
    }

    @Test
    void repeatedKeyReplacedEverywhere() {
        PromptTemplate t = PromptTemplate.of("{x} + {x} = {y}");
        assertEquals("2 + 2 = 4", t.render(Map.of("x", "2", "y", "4")));
    }
}
