package au.clock.display;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class ForecastWeather {
    enum Condition {
        CLEAR("Clear"), PARTLY_CLOUDY("Partly cloudy"), CLOUDY("Cloudy"), FOG("Fog"),
        RAIN("Rain"), SNOW("Snow"), STORM("Thunderstorms");

        final String label;

        Condition(String label) {
            this.label = label;
        }
    }

    final Condition condition;
    final boolean day;
    final boolean bom;

    private ForecastWeather(Condition condition, boolean day, boolean bom) {
        this.condition = condition;
        this.day = day;
        this.bom = bom;
    }

    static ForecastWeather load(double latitude, double longitude) throws Exception {
        try {
            return request(latitude, longitude, true);
        } catch (Exception unavailable) {
            return request(latitude, longitude, false);
        }
    }

    private static ForecastWeather request(double latitude, double longitude, boolean bom) throws Exception {
        String address = String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/%s?latitude=%.4f&longitude=%.4f&hourly=weather_code,is_day&forecast_hours=4&timeformat=unixtime",
                bom ? "bom" : "forecast", latitude, longitude);
        return parse(fetch(address), System.currentTimeMillis() / 1000, bom);
    }

    private static JSONObject fetch(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(7000);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Forecast returned " + connection.getResponseCode());
            try (InputStream input = connection.getInputStream();
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 65536) throw new IOException("Forecast response too large");
                    bytes.write(buffer, 0, count);
                }
                return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    static ForecastWeather parse(JSONObject response, long now, boolean bom) throws Exception {
        JSONObject hourly = response.getJSONObject("hourly");
        JSONArray times = hourly.getJSONArray("time");
        JSONArray codes = hourly.getJSONArray("weather_code");
        JSONArray daylight = hourly.getJSONArray("is_day");
        Condition expected = null;
        boolean day = true;
        for (int index = 0; index < times.length(); index++) {
            long time = times.getLong(index);
            if (time <= now || time > now + 3 * 3600) continue;
            Condition upcoming = classify(codes.optInt(index, -1));
            if (upcoming == null) continue;
            if (expected == null || upcoming.ordinal() > expected.ordinal()) {
                expected = upcoming;
                day = daylight.optInt(index, 1) == 1;
            }
        }
        if (expected == null) throw new IOException("No upcoming weather conditions");
        return new ForecastWeather(expected, day, bom);
    }

    static final class Day {
        final java.time.LocalDate date;
        final Condition condition;
        final String temperatures, chance, rain;

        Day(JSONObject daily, int index) throws Exception {
            date = java.time.LocalDate.parse(daily.getJSONArray("time").getString(index));
            condition = classify(daily.getJSONArray("weather_code").optInt(index, -1));
            temperatures = number(daily, "temperature_2m_max", index, "%.0f°") + " / "
                    + number(daily, "temperature_2m_min", index, "%.0f°");
            chance = number(daily, "precipitation_probability_max", index, "%.0f%%");
            rain = number(daily, "rain_sum", index, "%.1f mm");
        }

        Day(java.time.LocalDate date, Condition condition, double high, double low, double probability, double amount) {
            this.date = date;
            this.condition = condition;
            temperatures = format(high, "%.0f°") + " / " + format(low, "%.0f°");
            chance = format(probability, "%.0f%%");
            rain = format(amount, "%.1f mm");
        }

        private static String format(double value, String format) {
            return Double.isFinite(value) ? String.format(Locale.getDefault(), format, value) : "—";
        }

        private static String number(JSONObject daily, String field, int index, String format) {
            JSONArray values = daily.optJSONArray(field);
            double value = values == null ? Double.NaN : values.optDouble(index, Double.NaN);
            return Double.isFinite(value) ? String.format(Locale.getDefault(), format, value) : "—";
        }
    }

    static final class Outlook {
        final java.time.ZoneId zone;
        final java.time.LocalDate start;
        final Day[] daily;
        final JSONObject hourly;

        Outlook(JSONObject response) throws Exception {
            zone = java.time.ZoneId.of(response.getString("timezone"));
            JSONObject data = response.getJSONObject("daily");
            daily = new Day[4];
            for (int i = 0; i < daily.length; i++) daily[i] = new Day(data, i);
            start = daily[0].date;
            hourly = response.getJSONObject("hourly");
        }

        boolean isCurrent() {
            return start.equals(java.time.LocalDate.now(zone));
        }

        Day[] periods() {
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now(zone);
            long current = now.toEpochSecond();
            long hourStart = current - now.getMinute() * 60L - now.getSecond();
            long midnight = now.toLocalDate().plusDays(1).atStartOfDay(zone).toEpochSecond();
            double high = Double.NEGATIVE_INFINITY, low = Double.POSITIVE_INFINITY;
            double chance = Double.NEGATIVE_INFINITY, rain = 0;
            boolean completeRain = true;
            Condition condition = null;
            JSONArray times = hourly.optJSONArray("time");
            for (int i = 0; i < times.length(); i++) {
                long t = java.time.LocalDateTime.parse(times.optString(i)).atZone(zone).toEpochSecond();
                if (t >= hourStart && t < midnight) {
                    double temp = value("temperature_2m", i);
                    if (Double.isFinite(temp)) {
                        high = Math.max(high, temp);
                        low = Math.min(low, temp);
                    }
                    double code = value("weather_code", i);
                    Condition next = Double.isFinite(code) ? classify((int) code) : null;
                    if (next != null && (condition == null || next.ordinal() > condition.ordinal())) condition = next;
                }
                // Precipitation values cover the preceding hour. Count only its overlap with now to midnight.
                long overlap = Math.min(t, midnight) - Math.max(t - 3600, current);
                if (overlap > 0) {
                    double amount = value("rain", i);
                    if (Double.isFinite(amount)) rain += amount * overlap / 3600.0;
                    else completeRain = false;
                    double probability = value("precipitation_probability", i);
                    if (Double.isFinite(probability)) chance = Math.max(chance, probability);
                }
            }
            return new Day[] {new Day(start, condition, high, low, chance, completeRain ? rain : Double.NaN),
                    daily[1], daily[2], daily[3]};
        }

        private double value(String key, int index) {
            JSONArray values = hourly.optJSONArray(key);
            return values == null ? Double.NaN : values.optDouble(index, Double.NaN);
        }
    }

    static Outlook loadDays(double latitude, double longitude) throws Exception {
        String address = String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&timezone=auto&forecast_days=4"
                        + "&hourly=temperature_2m,weather_code,precipitation_probability,rain"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,rain_sum",
                latitude, longitude);
        return new Outlook(fetch(address));
    }

    private static Condition classify(int code) {
        if (code == 0) return Condition.CLEAR;
        if (code == 1 || code == 2) return Condition.PARTLY_CLOUDY;
        if (code == 3) return Condition.CLOUDY;
        if (code == 45 || code == 48) return Condition.FOG;
        if (code == 71 || code == 73 || code == 75 || code == 77 || code == 85 || code == 86) return Condition.SNOW;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return Condition.RAIN;
        if (code == 95 || code == 96 || code == 99) return Condition.STORM;
        return null;
    }
}
