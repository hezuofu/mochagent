package io.sketch.mochaagents.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class DispatcherTest {

    @Test
    void dispatchesToRegisteredCommand() {
        Dispatcher d = new Dispatcher();
        d.on("hello", (args, out, err) -> { out.println("Hello!"); return 0; });

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        d.dispatch(new String[]{"hello"}, new PrintStream(buf), System.err);
        assertTrue(buf.toString().contains("Hello!"));
    }

    @Test
    void fallbackWhenNoMatch() {
        Dispatcher d = new Dispatcher();
        d.otherwise((args, out, err) -> { out.println("fallback"); return 42; });

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code = d.dispatch(new String[]{"unknown"}, new PrintStream(buf), System.err);
        assertEquals(42, code);
        assertTrue(buf.toString().contains("fallback"));
    }

    @Test
    void noArgsUsesFallback() {
        Dispatcher d = new Dispatcher();
        d.otherwise((args, out, err) -> 0);
        assertEquals(0, d.dispatch(new String[]{}, System.out, System.err));
    }

    @Test
    void noMatchNoFallbackReturns1() {
        Dispatcher d = new Dispatcher();
        assertEquals(1, d.dispatch(new String[]{"x"}, System.out, System.err));
    }

    @Test
    void aliasRouting() {
        Dispatcher d = new Dispatcher();
        d.on(new String[]{"ls", "list", "dir"}, (a, o, e) -> { o.print("listed"); return 0; });

        var buf = new ByteArrayOutputStream();
        d.dispatch(new String[]{"list"}, new PrintStream(buf), System.err);
        assertTrue(buf.toString().contains("listed"));

        buf.reset();
        d.dispatch(new String[]{"ls"}, new PrintStream(buf), System.err);
        assertTrue(buf.toString().contains("listed"));
    }
}
