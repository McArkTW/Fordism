package tw.mcark.tony.fordism.auth;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.tinylog.Logger;

/**
 * An issuer's signing keys, fetched from its JWKS endpoint and cached.
 *
 * <p>Cached because every sign-in would otherwise be a round trip, and refetched on a miss because
 * issuers rotate keys without warning — an unknown key id means "we are behind", not "forged".
 * The refresh is rate-limited so an attacker cannot turn a stream of bogus key ids into a stream of
 * outbound requests.
 *
 * <p>This was {@code GoogleJsonWebKeys}; the endpoint became a constructor argument when a second
 * OIDC provider arrived. Nothing else about it changed — one cache per issuer, because two issuers
 * can and do publish keys under the same id.
 */
public final class RemoteJsonWebKeys implements JsonWebKeys {
    private static final Gson GSON = new Gson();
    private static final long REFRESH_FLOOR_MILLIS = 60_000;

    private final HttpClient http;
    private final URI jwksUrl;
    private final Map<String, RSAPublicKey> cache = new ConcurrentHashMap<>();
    private volatile long refreshedAt;

    public RemoteJsonWebKeys(HttpClient http, String jwksUrl) {
        this.http = http;
        this.jwksUrl = URI.create(jwksUrl);
    }

    /** Google's published keys. */
    public static RemoteJsonWebKeys google(HttpClient http) {
        return new RemoteJsonWebKeys(http, "https://www.googleapis.com/oauth2/v3/certs");
    }

    /** One Entra tenant's published keys — tenant-scoped, like the issuer they sign. */
    public static RemoteJsonWebKeys microsoft(HttpClient http, String tenantId) {
        return new RemoteJsonWebKeys(http,
                "https://login.microsoftonline.com/" + tenantId + "/discovery/v2.0/keys");
    }

    @Override
    public Optional<RSAPublicKey> find(String keyId) {
        if (keyId == null || keyId.isBlank()) {
            return Optional.empty();
        }
        RSAPublicKey cached = cache.get(keyId);
        if (cached != null) {
            return Optional.of(cached);
        }
        refresh();
        return Optional.ofNullable(cache.get(keyId));
    }

    private synchronized void refresh() {
        long now = System.currentTimeMillis();
        if (now - refreshedAt < REFRESH_FLOOR_MILLIS) {
            return;
        }
        refreshedAt = now;
        try {
            HttpRequest request = HttpRequest.newBuilder(jwksUrl)
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                Logger.warn("JWKS {} returned {}", jwksUrl, response.statusCode());
                return;
            }
            absorb(GSON.fromJson(response.body(), JsonObject.class));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.warn("interrupted while fetching JWKS {}", jwksUrl);
        } catch (RuntimeException | java.io.IOException e) {
            Logger.warn("could not fetch JWKS {}: {}", jwksUrl, e.getMessage());
        }
    }

    private void absorb(JsonObject document) {
        if (document == null || !document.has("keys")) {
            return;
        }
        JsonArray keys = document.getAsJsonArray("keys");
        for (JsonElement element : keys) {
            JsonObject key = element.getAsJsonObject();
            if (!key.has("kid") || !key.has("n") || !key.has("e")) {
                continue;
            }
            rsaKey(key.get("n").getAsString(), key.get("e").getAsString())
                    .ifPresent(parsed -> cache.put(key.get("kid").getAsString(), parsed));
        }
    }

    private static Optional<RSAPublicKey> rsaKey(String modulus, String exponent) {
        try {
            RSAPublicKeySpec spec = new RSAPublicKeySpec(
                    new BigInteger(1, Base64.getUrlDecoder().decode(modulus)),
                    new BigInteger(1, Base64.getUrlDecoder().decode(exponent)));
            return Optional.of((RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec));
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            Logger.warn("unusable key in JWKS: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
