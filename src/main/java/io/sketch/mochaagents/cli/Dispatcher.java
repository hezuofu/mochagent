package io.sketch.mochaagents.cli;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Routes argv[0] to a registered CliCommand, or falls back to a default. */
public class Dispatcher {

    private final Map<String, CliCommand> routes = new LinkedHashMap<>();
    private CliCommand fallback;

    public Dispatcher on(String name, CliCommand cmd) { routes.put(name, cmd); return this; }
    public Dispatcher on(String[] aliases, CliCommand cmd) {
        for (String a : aliases) routes.put(a, cmd); return this;
    }
    public Dispatcher otherwise(CliCommand cmd) { this.fallback = cmd; return this; }

    public int dispatch(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) return fallback != null ? fallback.run(args, out, err) : 1;
        CliCommand cmd = routes.get(args[0]);
        String[] rest = args.length > 1 ? java.util.Arrays.copyOfRange(args, 1, args.length) : new String[0];
        CliCommand target = cmd != null ? cmd : fallback;
        if (target == null) return 1;
        return target.run(rest, out, err);
    }
}
