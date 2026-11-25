package au.clock.display;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class PanTechWeather {
    static BomWeather.Reading temperature(String address) throws Exception {
        URL url = new URL("http", address, "/get_livedata_info");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            JSONArray fields = new JSONObject(body.toString()).getJSONArray("common_list");
            for (int i = 0; i < fields.length(); i++) {
                JSONObject field = fields.getJSONObject(i);
                if (!"0x02".equalsIgnoreCase(field.optString("id"))) continue;
                double value = field.getDouble("val");
                String unit = field.getString("unit").replace("°", "").trim();
                if ("F".equalsIgnoreCase(unit)) value = (value - 32) * 5 / 9;
                else if (!"C".equalsIgnoreCase(unit)) throw new IOException("Unknown temperature unit");
                if (Double.isFinite(value)) return new BomWeather.Reading("PanTech", value);
            }
            throw new IOException("Outdoor sensor reading unavailable");
        } finally {
            connection.disconnect();
        }
    }
}
