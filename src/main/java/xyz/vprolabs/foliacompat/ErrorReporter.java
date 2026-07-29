package xyz.vprolabs.foliacompat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

public final class ErrorReporter {

    private static final String WEBHOOK_URL = "https://www.vprolabs.xyz/api/public-error-webhook";
    private static final String HMAC_SALT = "vprolabs-public-error-key";
    private static final String PLUGIN_ID = "foliacompat";

    private static volatile boolean enabled = true;
    private static volatile String pluginVersion = "1.0.0";
    private static final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private ErrorReporter() {}

    public static void setEnabled(boolean e) { enabled = e; }
    public static void setPluginVersion(String v) { if (v != null && !v.isEmpty()) pluginVersion = v; }

    public static void report(String pluginName, Throwable error) {
        if (!enabled || error == null) return;
        String message = error.getMessage() != null ? error.getMessage() : error.getClass().getName();
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        String stack = sw.toString();
        String cause = error.getCause() != null ? error.getCause().getMessage() : null;
        send("error", message, pluginName, stack, cause);
    }

    public static void report(String pluginName, String message) {
        if (!enabled || message == null) return;
        send("error", message, pluginName, null, null);
    }

    private static void send(String type, String message, String pluginName, String stack, String cause) {
        try {
            String body = buildJson(type, message, pluginName, stack, cause);
            long timestamp = System.currentTimeMillis();
            String hmac = computeHmac(PLUGIN_ID, timestamp, body);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(WEBHOOK_URL))
                .header("Content-Type", "application/json")
                .header("X-Plugin-Id", PLUGIN_ID)
                .header("X-Timestamp", String.valueOf(timestamp))
                .header("X-HMAC", hmac)
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            if (code != 200) {
                DebugUtil.info("ErrorReporter HTTP " + code);
            }
        } catch (Exception e) {
            DebugUtil.info("ErrorReporter failed: " + e.getClass().getSimpleName()
                + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }
    }

    private static String buildJson(String type, String message, String pluginName, String stack, String cause) {
        String server = "";
        try { server = org.bukkit.Bukkit.getVersion(); } catch (Exception ignored) {}

        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"").append(escape(type)).append("\"");
        sb.append(",\"message\":\"").append(escape(message)).append("\"");
        sb.append(",\"plugin\":\"").append(escape(pluginName)).append("\"");
        sb.append(",\"version\":\"").append(escape(pluginVersion)).append("\"");
        if (!server.isEmpty()) sb.append(",\"server\":\"").append(escape(server)).append("\"");
        if (stack != null) sb.append(",\"stack\":\"").append(escape(stack)).append("\"");
        if (cause != null) sb.append(",\"cause\":\"").append(escape(cause)).append("\"");
        sb.append("}");
        return sb.toString();
    }

    private static String computeHmac(String pluginId, long timestamp, String body) throws Exception {
        String bodyHash = sha256(body);
        String toSign = pluginId + ":" + timestamp + ":" + bodyHash;
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec key = new SecretKeySpec(HMAC_SALT.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(key);
        byte[] hmacBytes = mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(hmacBytes);
    }

    private static String sha256(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(hash);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xFF;
            hex.append(HEX_CHARS[v >>> 4]);
            hex.append(HEX_CHARS[v & 0xF]);
        }
        return hex.toString();
    }
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
