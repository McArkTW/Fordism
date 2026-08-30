package tw.mcark.tony.fordism.auth;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.tinylog.Logger;

/**
 * The three lines of HTTP an OAuth code flow actually needs, shared by the two providers.
 *
 * <p>{@code java.net.http} rather than an OAuth library: the flow is one form post and one or two
 * GETs, and the parts worth getting right — verifying an id_token, demanding a verified email,
 * refusing an unknown address — are exactly the parts a library would not decide for us.
 */
final class OAuthHttp {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private OAuthHttp() {}

    /** {@code a=1&b=2}, percent-encoded. */
    static String form(Map<String, String> fields) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(encode(field.getKey())).append('=').append(encode(field.getValue()));
        }
        return out.toString();
    }

    static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** POST a form, ask for JSON back. Empty on any non-2xx or transport failure. */
    static Optional<String> postForm(HttpClient http, URI url, String body) {
        HttpRequest request = HttpRequest.newBuilder(url).timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return send(http, request);
    }

    /** GET an API resource with a bearer access token. */
    static Optional<String> getWithToken(HttpClient http, URI url, String accessToken) {
        HttpRequest request = HttpRequest.newBuilder(url).timeout(TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "fordism-core")
                .GET().build();
        return send(http, request);
    }

    private static Optional<String> send(HttpClient http, HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                Logger.warn("{} {} returned {}", request.method(), request.uri(), response.statusCode());
                return Optional.empty();
            }
            return Optional.of(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            Logger.warn("{} {} failed: {}", request.method(), request.uri(), e.getMessage());
            return Optional.empty();
        }
    }
}
