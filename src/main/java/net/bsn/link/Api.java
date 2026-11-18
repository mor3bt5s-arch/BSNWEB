package net.bsn.link;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** HTTP client for the BSN website (Cloudflare Worker). Always called from async threads. */
final class Api {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String base;
    private final String key;

    Api(String base, String key) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.key = key;
    }

    Map<String, Object> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
    }

    Map<String, Object> post(String path, Object body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("content-type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(HttpRequest.Builder rb) throws Exception {
        HttpRequest req = rb.timeout(Duration.ofSeconds(20))
                .header("authorization", "Bearer " + key)
                .header("user-agent", "BSNLink/1.0")
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() / 100 != 2) {
            String b = res.body() == null ? "" : res.body();
            if (res.statusCode() == 401) throw new IOException("HTTP 401: مفتاح السيرفر (api-key) غلط أو اتغيّر");
            throw new IOException("HTTP " + res.statusCode() + " " + (b.length() > 120 ? b.substring(0, 120) : b));
        }
        Object o = Json.parse(res.body());
        if (!(o instanceof Map)) throw new IOException("رد غير متوقع من الموقع");
        return (Map<String, Object>) o;
    }
}
