package io.sketch.mochaagents.examples.tools;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Currency converter — calls ExchangeRate-API (free tier, no key for EUR base).
 * @author lanxia39@163.com
 */
public final class CurrencyTool implements Tool {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @Override public String getName() { return "convert_currency"; }
    @Override public String getDescription() {
        return "Convert between currencies. Input: amount (number), from_currency (e.g. USD), to_currency (e.g. EUR).";
    }
    @Override public Map<String, ToolInput> getInputs() {
        return Map.of(
                "amount", ToolInput.string("Amount to convert"),
                "from_currency", ToolInput.string("Source currency code"),
                "to_currency", ToolInput.string("Target currency code"));
    }
    @Override public String getOutputType() { return "string"; }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }

    @Override
    public Object call(Map<String, Object> args) {
        double amount = Double.parseDouble(String.valueOf(args.getOrDefault("amount", "1")));
        String from = String.valueOf(args.getOrDefault("from_currency", "USD")).toUpperCase();
        String to = String.valueOf(args.getOrDefault("to_currency", "EUR")).toUpperCase();

        try {
            // Open Exchange Rates free API (no key needed for basic endpoint)
            String url = "https://open.er-api.com/v6/latest/" + from;
            String resp = HTTP.send(HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();

            // Simple JSON parsing: find the target currency rate
            String search = "\"" + to + "\":";
            int idx = resp.indexOf(search);
            if (idx < 0) throw new RuntimeException("Currency " + to + " not found");

            String rateStr = resp.substring(idx + search.length(), resp.indexOf(",", idx)).trim();
            double rate = Double.parseDouble(rateStr);
            double converted = amount * rate;

            return String.format("%.2f %s = %.2f %s (rate: %.4f)", amount, from, converted, to, rate);
        } catch (Exception e) {
            return "Currency conversion failed: " + e.getMessage();
        }
    }
}
