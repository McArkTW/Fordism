package tw.mcark.tony.fordism.auth;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.tinylog.Logger;

/**
 * GitHub sign-in: authorization code, then the API calls that establish who the token belongs to.
 *
 * <p>Two calls, not one. {@code /user} gives the account and its stable numeric id, but its
 * {@code email} field is whatever the person chose to display publicly — it can be blank, and it is
 * not proof. The address Fordism keys an account on comes from {@code /user/emails} and only when
 * GitHub marks it both primary and verified.
 *
 * <p>No PKCE: GitHub's OAuth app flow does not offer it, and the exchange already carries the
 * client secret over TLS.
 */
public final class GitHubOAuth implements ExternalAuthProvider {
    private static final String AUTHORIZE_URL = "https://github.com/login/oauth/authorize";
    private static final String TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String USER_URL = "https://api.github.com/user";
    private static final String EMAILS_URL = "https://api.github.com/user/emails";
    private static final Gson GSON = new Gson();

    private final AuthConfiguration configuration;
    private final HttpClient http;

    public GitHubOAuth(AuthConfiguration configuration, HttpClient http) {
        this.configuration = configuration;
        this.http = http;
    }

    @Override
    public String authorizationUrl(LoginAttempt attempt) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("client_id", configuration.githubClientId());
        parameters.put("redirect_uri", configuration.redirectUri(AuthProviderId.GITHUB));
        parameters.put("scope", "read:user user:email");
        parameters.put("state", attempt.state());
        return AUTHORIZE_URL + "?" + OAuthHttp.form(parameters);
    }

    @Override
    public Optional<ExternalIdentity> identify(LoginCallback callback) {
        String accessToken = exchange(callback.code()).orElse(null);
        if (accessToken == null) {
            return Optional.empty();
        }
        JsonObject account = object(OAuthHttp.getWithToken(http, URI.create(USER_URL), accessToken));
        if (account == null || !account.has("id")) {
            Logger.warn("GitHub /user returned no account");
            return Optional.empty();
        }
        String email = primaryVerifiedEmail(accessToken).orElse(null);
        if (email == null) {
            Logger.warn("GitHub account {} has no primary verified email", text(account, "login"));
            return Optional.empty();
        }
        String name = text(account, "name");
        return Optional.of(new ExternalIdentity(AuthProviderId.GITHUB, account.get("id").getAsString(),
                email, name.isBlank() ? text(account, "login") : name));
    }

    private Optional<String> exchange(String code) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("client_id", configuration.githubClientId());
        parameters.put("client_secret", configuration.githubClientSecret());
        parameters.put("code", code);
        parameters.put("redirect_uri", configuration.redirectUri(AuthProviderId.GITHUB));

        JsonObject response = object(
                OAuthHttp.postForm(http, URI.create(TOKEN_URL), OAuthHttp.form(parameters)));
        if (response == null || !response.has("access_token")) {
            Logger.warn("GitHub token exchange failed: {}", response == null ? "no response" : response);
            return Optional.empty();
        }
        return Optional.of(response.get("access_token").getAsString());
    }

    private Optional<String> primaryVerifiedEmail(String accessToken) {
        String body = OAuthHttp.getWithToken(http, URI.create(EMAILS_URL), accessToken).orElse(null);
        if (body == null) {
            return Optional.empty();
        }
        JsonArray addresses = GSON.fromJson(body, JsonArray.class);
        if (addresses == null) {
            return Optional.empty();
        }
        for (JsonElement element : addresses) {
            JsonObject address = element.getAsJsonObject();
            if (flag(address, "primary") && flag(address, "verified")) {
                return Optional.of(text(address, "email"));
            }
        }
        return Optional.empty();
    }

    private static JsonObject object(Optional<String> body) {
        return body.map(text -> GSON.fromJson(text, JsonObject.class)).orElse(null);
    }

    private static String text(JsonObject json, String field) {
        return json.has(field) && json.get(field).isJsonPrimitive() ? json.get(field).getAsString() : "";
    }

    private static boolean flag(JsonObject json, String field) {
        return json.has(field) && json.get(field).isJsonPrimitive() && json.get(field).getAsBoolean();
    }
}
