package au.clock.display;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

final class BomWeather {
    static final class Locality {
        final String name;
        final String state;
        final double latitude;
        final double longitude;

        Locality(String name, String state, double latitude, double longitude) {
            this.name = name;
            this.state = state;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        @Override public String toString() {
            return name + ", " + state;
        }
    }

    static final class Reading {
        final String station;
        final double temperature;

        Reading(String station, double temperature) {
            this.station = station;
            this.temperature = temperature;
        }
    }

    static List<Locality> find(InputStream source, String postcode) throws IOException {
        List<Locality> places = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(postcode + "\t")) continue;
                String[] fields = line.split("\t");
                if (fields.length == 5) {
                    places.add(new Locality(fields[1], fields[2], Double.parseDouble(fields[3]), Double.parseDouble(fields[4])));
                }
            }
        }
        places.sort((first, second) -> first.name.compareToIgnoreCase(second.name));
        return places;
    }

    static Reading temperature(String state, double latitude, double longitude) throws Exception {
        String region;
        switch (state) {
            case "NT": region = "D"; break;
            case "NSW":
            case "ACT": region = "N"; break;
            case "QLD": region = "Q"; break;
            case "SA": region = "S"; break;
            case "TAS": region = "T"; break;
            case "VIC": region = "V"; break;
            case "WA": region = "W"; break;
            default: throw new IOException("No statewide BOM feed for " + state);
        }
        URL url = new URL("https://reg.bom.gov.au/fwo/ID" + region + "60920.xml");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(7000);
        connection.setRequestProperty("Accept", "application/xml, text/xml");
        connection.setRequestProperty("User-Agent", "BedsideClock/2.0 (Android)");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("BOM returned " + connection.getResponseCode());
            try (InputStream input = connection.getInputStream()) {
                return nearest(input, latitude, longitude, Instant.now());
            }
        } finally {
            connection.disconnect();
        }
    }

    static Reading nearest(InputStream source, double latitude, double longitude, Instant now) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = source.read(buffer)) != -1) {
            if (bytes.size() + count > 2 * 1024 * 1024) throw new IOException("BOM feed too large");
            bytes.write(buffer, 0, count);
        }
        String xml = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        if (xml.contains("<!DOCTYPE")) throw new IOException("Unexpected DOCTYPE in BOM feed");
        javax.xml.parsers.DocumentBuilder parser = factory.newDocumentBuilder();
        parser.setEntityResolver((publicId, systemId) -> new org.xml.sax.InputSource(new StringReader("")));
        NodeList stations = parser.parse(new org.xml.sax.InputSource(new StringReader(xml))).getElementsByTagName("station");
        Reading closest = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < stations.getLength(); index++) {
            Element station = (Element) stations.item(index);
            try {
                double stationLat = Double.parseDouble(station.getAttribute("lat"));
                double stationLon = Double.parseDouble(station.getAttribute("lon"));
                double latDelta = Math.toRadians(stationLat - latitude);
                double lonDelta = Math.toRadians(stationLon - longitude);
                double distance = Math.pow(Math.sin(latDelta / 2), 2) + Math.cos(Math.toRadians(latitude))
                        * Math.cos(Math.toRadians(stationLat)) * Math.pow(Math.sin(lonDelta / 2), 2);
                if (distance >= closestDistance) continue;
                double temperature = recentTemperature(station, now);
                if (!Double.isFinite(temperature)) continue;
                String description = station.getAttribute("description");
                closest = new Reading(description.isEmpty() ? station.getAttribute("stn-name") : description, temperature);
                closestDistance = distance;
            } catch (NumberFormatException ignored) {
                // The feed may include stations without coordinates.
            }
        }
        if (closest == null) throw new IOException("No recent temperature in BOM feed");
        return closest;
    }

    private static double recentTemperature(Element station, Instant now) {
        NodeList periods = station.getElementsByTagName("period");
        for (int index = 0; index < periods.getLength(); index++) {
            Element period = (Element) periods.item(index);
            try {
                Instant observed = OffsetDateTime.parse(period.getAttribute("time-utc")).toInstant();
                if (observed.isBefore(now.minus(Duration.ofMinutes(90))) ||
                        observed.isAfter(now.plus(Duration.ofMinutes(5)))) continue;
                NodeList readings = period.getElementsByTagName("element");
                for (int item = 0; item < readings.getLength(); item++) {
                    Element reading = (Element) readings.item(item);
                    if ("air_temperature".equals(reading.getAttribute("type"))) {
                        return Double.parseDouble(reading.getTextContent());
                    }
                }
            } catch (java.time.format.DateTimeParseException | NumberFormatException ignored) {
                // Missing observation values are expected in these feeds.
            }
        }
        return Double.NaN;
    }
}
