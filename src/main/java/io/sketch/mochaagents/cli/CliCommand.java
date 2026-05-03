package io.sketch.mochaagents.cli;

import java.io.PrintStream;

/** CLI subcommand — a function of (args, stdout, stderr) → exitCode (0=ok). */
@FunctionalInterface
public interface CliCommand {
    int run(String[] args, PrintStream out, PrintStream err);
}
