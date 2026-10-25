package com.n500.remote;

import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.InetAddress;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Experimental local-only protocol, never open a port-forward to the internet. */
final class TvClient {
    private final String host;
    private final int port;
    private volatile String token = "";
    TvClient(String endpoint) throws Exception {
        String input = endpoint.trim();
        if (!input.matches("[0-9.]+(:[0-9]{2,5})?")) throw new IllegalArgumentException("Enter projector IP, e.g. 192.168.100.243:8765");
        String[] parts = input.split(":");
        host = parts[0];
        port = parts.length == 2 ? Integer.parseInt(parts[1]) : 8765;
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Port must be 1024–65535");
        InetAddress address = InetAddress.getByName(host);
        byte[] b = address.getAddress();
        if (b.length != 4) throw new IllegalArgumentException("IPv4 required");
        int first = b[0] & 255, second = b[1] & 255;
        boolean local = first == 10 || first == 192 && second == 168 || first == 172 && second >= 16 && second <= 31;
        if (!local) throw new IllegalArgumentException("Only private home Wi-Fi IPv4 addresses are accepted");
    }
    /** Rehydrate ONLY a token decrypted from the AndroidKeyStore. No new PIN needed. */
    static TvClient restore(String endpoint, String savedToken) throws Exception {
        if(savedToken==null || !savedToken.matches("[a-fA-F0-9]{64}"))
            throw new IllegalArgumentException("Invalid stored token");
        TvClient client = new TvClient(endpoint);
        client.token = savedToken;
        return client;
    }
    String pairingToken() { return token; }
    String address() { return host + ":" + port; }
    boolean isPaired() { return token.length() > 0; }
    void clearPairing() { token = ""; }
    JSONObject pair(String pin) throws Exception {
        if (!pin.matches("[0-9]{6}")) throw new IllegalArgumentException("Enter the 6-digit pairing code shown on projector");
        JSONObject body = new JSONObject(); body.put("pin", pin);
        JSONObject response = new JSONObject(new String(request("POST", "/v1/pair", body, false), StandardCharsets.UTF_8));
        String received = response.optString("token", "");
        if (!received.matches("[0-9a-fA-F]{64}")) throw new IllegalStateException("Projector returned an invalid pairing token");
        token = received;
        return response;
    }
    JSONObject status() throws Exception {
        return new JSONObject(new String(request("GET", "/v1/status", null, true), StandardCharsets.UTF_8));
    }
    JSONObject key(String name) throws Exception {
        JSONObject body = new JSONObject(); body.put("key", name);
        return new JSONObject(new String(request("POST", "/v1/key", body, true), StandardCharsets.UTF_8));
    }
    JSONObject apps() throws Exception {
        return new JSONObject(new String(request("GET", "/v1/apps", null, true), StandardCharsets.UTF_8));
    }
    JSONObject launch(String pkg) throws Exception {
        JSONObject body = new JSONObject(); body.put("package", pkg);
        return new JSONObject(new String(request("POST", "/v1/launch", body, true), StandardCharsets.UTF_8));
    }
    JSONObject startView() throws Exception {
        return new JSONObject(new String(request("POST", "/v1/live/start", new JSONObject(), true), StandardCharsets.UTF_8));
    }
    void stopView() throws Exception { request("POST", "/v1/live/stop", new JSONObject(), true); }
    byte[] frame() throws Exception { return request("GET", "/v1/live/frame", null, true); }

    private byte[] request(String method, String path, JSONObject body, boolean auth) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://" + address() + path).openConnection();
        connection.setConnectTimeout(2500);
        connection.setReadTimeout(3500);
        connection.setUseCaches(false);
        connection.setRequestMethod(method);
        if (auth) {
            if (!isPaired()) throw new IllegalStateException("Pair with your projector first");
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        }
        try {
            int code = connection.getResponseCode();
            if (code == 204) return new byte[0];
            if (code < 200 || code > 299) {
                throw new IllegalStateException("Projector HTTP " + code + (code == 401 ? " (pair again)" : ""));
            }
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream result = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) {
                    result.write(buffer, 0, count);
                    if (result.size() > 2_000_000) throw new IllegalStateException("Screen frame too large");
                }
                return result.toByteArray();
            }
        } finally { connection.disconnect(); }
    }
}
