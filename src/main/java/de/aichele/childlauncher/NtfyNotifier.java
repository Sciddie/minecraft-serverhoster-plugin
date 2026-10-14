package de.aichele.childlauncher;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

final class NtfyNotifier {
    record Settings(boolean enabled, String server, String topic) {}

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private NtfyNotifier() {}

    static void send(Settings settings, String token, String title, String message, String priority) throws IOException {
        if (!settings.enabled()) return;
        URI target = target(settings.server(), settings.topic());
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(8))
                .header("Title", encodeHeader(title))
                .header("Priority", validPriority(priority))
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(message, StandardCharsets.UTF_8));
        if (token != null && !token.isBlank()) request.header("Authorization", "Bearer " + token);
        try {
            HttpResponse<Void> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IOException("ntfy returned HTTP " + response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ntfy request interrupted", e);
        }
    }

    static URI target(String server, String topic) throws IOException {
        if (server == null || server.isBlank()) throw new IOException("notifications.ntfy.server is required");
        if (topic == null || !topic.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IOException("notifications.ntfy.topic must contain 1-64 letters, digits, '_' or '-'");
        try {
            URI base = URI.create(server.trim());
            if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null
                    || base.getRawUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null) {
                throw new IOException("ntfy server must be an HTTPS base URL without credentials, query or fragment");
            }
            String path = base.getRawPath() == null ? "" : base.getRawPath().replaceAll("/+$", "");
            return URI.create("https://" + base.getRawAuthority() + path + "/" + topic);
        } catch (IllegalArgumentException e) {
            throw new IOException("notifications.ntfy.server is not a valid URL", e);
        }
    }

    private static String encodeHeader(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String validPriority(String priority) {
        String value = priority == null ? "3" : priority.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "1", "2", "3", "4", "5" -> value;
            default -> "3";
        };
    }
}
