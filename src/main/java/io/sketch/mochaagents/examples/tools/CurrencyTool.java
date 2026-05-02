package io.sketch.mochaagents.examples.tools;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;

import java.util.Map;

/**
 * 货币转换工具 — 对应 smolagents 的 convert_currency.
 */
public final class CurrencyTool implements Tool {
    @Override public String getName() { return "convert_currency"; }
    @Override public String getDescription() {
        return "Converts an amount from one currency to another. Input: amount (number), from_currency, to_currency.";
    }
    @Override public Map<String, ToolInput> getInputs() {
        return Map.of(
                "amount", ToolInput.string("The amount to convert"),
                "from_currency", ToolInput.string("Source currency code, e.g. USD"),
                "to_currency", ToolInput.string("Target currency code, e.g. EUR")
        );
    }
    @Override public String getOutputType() { return "string"; }
    @Override public Object call(Map<String, Object> args) {
        double amount = Double.parseDouble(String.valueOf(args.getOrDefault("amount", "0")));
        String to = String.valueOf(args.getOrDefault("to_currency", "EUR"));
        // 简化的固定汇率
        double rate = "EUR".equalsIgnoreCase(to) ? 0.92 : 1.0;
        double converted = amount * rate;
        return amount + " " + args.get("from_currency")
                + " = " + String.format("%.2f", converted) + " " + to;
    }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
}
