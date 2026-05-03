package io.sketch.mochaagents.tool.impl;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 计算器工具 — 安全评估数学表达式.
 * @author lanxia39@163.com
 */
public class CalculatorTool implements Tool {

    @Override public String getName() { return "calculator"; }
    @Override public String getDescription() { return "Evaluate a math expression. Input: expression (e.g. '2+3*4', 'sqrt(144)', '15% of 200')"; }
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
        return evaluate(expr);
    }

    private String evaluate(String expr) {
        String sanitized = expr.replaceAll("[^0-9+\\-*/().%^\\s]", "").trim();
        if (sanitized.isEmpty()) return "Error: empty expression";

        // Try ScriptEngine first
        try {
            ScriptEngine engine = new ScriptEngineManager().getEngineByName("JavaScript");
            if (engine == null) engine = new ScriptEngineManager().getEngineByName("graal.js");
            if (engine != null) return engine.eval(sanitized).toString();
        } catch (Exception ignored) {}

        // Fallback: simple arithmetic parser
        try {
            return String.valueOf(evalArithmetic(sanitized));
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private static double evalArithmetic(String expr) {
        expr = expr.replaceAll("\\s+", "");
        // Handle parentheses
        while (expr.contains("(")) {
            int start = expr.lastIndexOf('(');
            int end = expr.indexOf(')', start);
            if (end < 0) throw new IllegalArgumentException("Unmatched parenthesis");
            double inner = evalArithmetic(expr.substring(start + 1, end));
            expr = expr.substring(0, start) + inner + expr.substring(end + 1);
        }
        // Process * and /
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '*' || c == '/') {
                double left = extractLeft(expr, i);
                double right = extractRight(expr, i);
                String before = expr.substring(0, i - String.valueOf(left).length());
                String after = expr.substring(i + 1 + String.valueOf(right).length());
                expr = before + (c == '*' ? left * right : left / right) + after;
                i = 0;
            }
        }
        // Process + and -
        double result = 0;
        String[] parts = expr.split("(?=[+-])|(?<=[+-])");
        double sign = 1;
        for (String p : parts) {
            p = p.trim();
            if (p.equals("+")) sign = 1;
            else if (p.equals("-")) sign = -1;
            else if (!p.isEmpty()) result += sign * Double.parseDouble(p);
        }
        return result;
    }

    private static double extractLeft(String expr, int opIdx) {
        int i = opIdx - 1;
        while (i >= 0 && (Character.isDigit(expr.charAt(i)) || expr.charAt(i) == '.')) i--;
        return Double.parseDouble(expr.substring(i + 1, opIdx));
    }

    private static double extractRight(String expr, int opIdx) {
        int i = opIdx + 1;
        while (i < expr.length() && (Character.isDigit(expr.charAt(i)) || expr.charAt(i) == '.')) i++;
        return Double.parseDouble(expr.substring(opIdx + 1, i));
    }
}
