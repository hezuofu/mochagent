package io.sketch.mochaagents.tool.impl;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calculator tool — evaluates math expressions safely.
 * @author lanxia39@163.com
 */
public class CalculatorTool implements Tool {

    @Override public String getName() { return "calculator"; }
    @Override public String getDescription() {
        return "Evaluate a math expression (e.g. '2+3*4', 'sqrt(144)'). "
             + "Input: 'expression' (string)";
    }
    @Override public Map<String, ToolInput> getInputs() {
        Map<String, ToolInput> in = new LinkedHashMap<>();
        in.put("expression", ToolInput.string("Math expression to evaluate"));
        return in;
    }
    @Override public String getOutputType() { return "string"; }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }

    @Override
    public Object call(Map<String, Object> args) {
        String expr = (String) args.getOrDefault("expression", "0");
        // Sanitize: allow digits, operators, parens, dot, spaces
        String sanitized = expr.replaceAll("[^0-9+\\-*/().\\s]", "").trim();
        if (sanitized.isEmpty()) return "Error: empty expression";

        // Try ScriptEngine (GraalJS/Nashorn)
        try {
            ScriptEngine engine = findEngine();
            if (engine != null) return engine.eval(sanitized).toString();
        } catch (Exception ignored) {}

        // Fallback: basic arithmetic (+, -, *, /)
        try {
            return String.valueOf(evalSimple(sanitized));
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private static ScriptEngine findEngine() {
        ScriptEngineManager mgr = new ScriptEngineManager();
        ScriptEngine e = mgr.getEngineByName("graal.js");
        if (e != null) return e;
        e = mgr.getEngineByName("JavaScript");
        if (e != null) return e;
        return mgr.getEngineByName("nashorn");
    }

    private static Object evalSimple(String expr) {
        // Handle basic arithmetic: only + - * /
        String[] terms = expr.split("(?=[+\\-])|(?<=[+\\-])");
        double result = 0;
        String pendingOp = "+";
        for (String term : terms) {
            term = term.trim();
            if (term.equals("+")) pendingOp = "+";
            else if (term.equals("-")) pendingOp = "-";
            else if (!term.isEmpty()) {
                double val = parseFactor(term);
                if (pendingOp.equals("+")) result += val;
                else result -= val;
            }
        }
        return result;
    }

    private static double parseFactor(String expr) {
        if (expr.contains("*") || expr.contains("/")) {
            String[] parts = expr.split("(?=[*/])|(?<=[*/])");
            double result = 1;
            String op = "*";
            for (String p : parts) {
                p = p.trim();
                if (p.equals("*")) op = "*";
                else if (p.equals("/")) op = "/";
                else if (!p.isEmpty()) {
                    double v = Double.parseDouble(p);
                    result = op.equals("*") ? result * v : result / v;
                }
            }
            return result;
        }
        return Double.parseDouble(expr);
    }
}
