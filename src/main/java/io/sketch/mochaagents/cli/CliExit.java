package io.sketch.mochaagents.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI exit helpers — consolidated exit points used by all subcommand handlers.
 *
 * <p>Aligns with {@code claude-code/src/cli/exit.ts}:
 * <ul>
 *   <li>{@link #cliOk(String)} — writes to stdout and exits 0</li>
 *   <li>{@link #cliError(String)} — writes to stderr and exits 1</li>
 * </ul>
 *
 * <p>The {@code : never} return type from TypeScript is emulated by declaring
 * these methods as returning {@code void} with a guaranteed {@code System.exit}
 * call, which prevents the JVM from executing any code after the call site.
 * @author lanxia39@163.com
 */
public final class CliExit {

    private static final Logger log = LoggerFactory.getLogger(CliExit.class);

    private CliExit() {
    }

    /**
     * Write a message to stdout (if non-null) and exit with code 0.
     * Equivalent to {@code cliOk(msg)} in exit.ts.
     */
    public static void cliOk(String msg) {
        log.info("CLI exiting OK: {}", msg != null ? msg : "(no message)");
        if (msg != null) {
            System.out.println(msg);
        }
        System.exit(0);
    }

    /**
     * Write an error message to stderr (if non-null) and exit with code 1.
     * Equivalent to {@code cliError(msg)} in exit.ts.
     */
    public static void cliError(String msg) {
        log.error("CLI exiting with error: {}", msg != null ? msg : "(no message)");
        if (msg != null) {
            System.err.println(msg);
        }
        System.exit(1);
    }

    /**
     * Alias for {@link #cliError(String)} — used in permission/auth check
     * paths where the naming convention {@code exitWithError(...)} is preferred.
     */
    public static void exitWithError(String msg) {
        log.warn("CLI exitWithError: {}", msg);
        cliError(msg);
    }
}
