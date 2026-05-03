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
 * Weather tool — calls Open-Meteo free API (no API key needed).
 * @author lanxia39@163.com
 */
public final class WeatherTool implements Tool {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @Override public String getName() { return "get_weather"; }
    @Override public String getDescription() {
        return "Get current weather. Input: location (city name) — uses Open-Meteo free API.";
    }
    @Override public Map<String, ToolInput> getInputs() {
        return Map.of("location", ToolInput.string("City name (e.g. Paris, London)"));
    }
    @Override public String getOutputType() { return "string"; }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }

    @Override
    public Object call(Map<String, Object> args) {
        String location = (String) args.getOrDefault("location", "Paris");
        try {
            // Geocode city → coordinates using Open-Meteo Geocoding API
            String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name="
                    + java.net.URLEncoder.encode(location, "UTF-8") + "&count=1";
            String geoResp = HTTP.send(HttpRequest.newBuilder().uri(URI.create(geoUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();

            double lat = 48.8566, lon = 2.3522; // default Paris
            if (geoResp.contains("\"latitude\"")) {
                int latIdx = geoResp.indexOf("\"latitude\"");
                int lonIdx = geoResp.indexOf("\"longitude\"");
                lat = Double.parseDouble(geoResp.substring(latIdx + 11, geoResp.indexOf(",", latIdx)));
                lon = Double.parseDouble(geoResp.substring(lonIdx + 12, geoResp.indexOf(",", lonIdx)));
            }

            // Fetch weather from Open-Meteo (free, no key)
            String weatherUrl = String.format(
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code",
                    lat, lon);
            String resp = HTTP.send(HttpRequest.newBuilder().uri(URI.create(weatherUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();

            double temp = 20;
            int code = 0;
            if (resp.contains("\"temperature_2m\"")) {
                int tIdx = resp.indexOf("\"temperature_2m\"");
                String tempStr = resp.substring(tIdx + 17, resp.indexOf(",", tIdx));
                temp = Double.parseDouble(tempStr);
            }
            if (resp.contains("\"weather_code\"")) {
                int wIdx = resp.indexOf("\"weather_code\"");
                code = Integer.parseInt(resp.substring(wIdx + 15, resp.indexOf("}", wIdx)));
            }

            String condition = switch (code) {
                case 0 -> "Clear sky"; case 1,2,3 -> "Partly cloudy";
                case 45,48 -> "Foggy"; case 51,53,55 -> "Drizzle";
                case 61,63,65 -> "Rain"; case 71,73,75 -> "Snow";
                case 80,81,82 -> "Rain showers"; case 95,96,99 -> "Thunderstorm";
                default -> "Unknown";
            };
            return String.format("Weather in %s: %s, %.1f°C (via Open-Meteo)", location, condition, temp);
        } catch (Exception e) {
            return "Weather unavailable: " + e.getMessage();
        }
    }
}
