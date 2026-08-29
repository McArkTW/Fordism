package tw.mcark.tony.fordism.auth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.tinylog.Logger;

/**
 * Google sign-in: authorization code + PKCE, then the {@code id_token} from the token response,
 * verified locally against Google's published keys.
 *
 * <p>The identity comes from the signed id_token rather than a userinfo call, so who the person is
 * rests on a signature this server checked, not on a response this server merely received.
 */
public final class GoogleOAuth implements ExternalAuthProvider {
    private static final String AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final Gson GSON = new Gson();

    private final AuthConfiguration configuration;
    private final HttpClient http;
    private final GoogleIdTokenVerifier idTokens;

    public GoogleOAuth(AuthConfiguration configuration, HttpClient http, GoogleIdTokenVerifier idTokens) {
        this.configuration = configuration;
        this.http = http;
        this.idTokens = idTokens;
    }

    @Override
    public String authorizationUrl(LoginAttempt attempt) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("client_id", configuration.googleClientId());
        parameters.put("redirect_uri", configuration.redirectUri(AuthProviderId.GOOGLE));
        parameters.put("response_type", "code");
        parameters.put("scope", "openid email profile");
        parameters.put("state", attempt.state());
        parameters.put("nonce", attempt.nonce());
        parameters.put("code_challenge", attempt.codeChallenge());
        parameters.put("code_challenge_method", "S256");
        // Google remembers a previous consent, so a second sign-in is one redirect with no clicks.
        parameters.put("prompt", "select_account");
        return AUTHORIZE_URL + "?" + OAuthHttp.form(parameters);
    }

    @Override
    public Optional<ExternalIdentity> identify(LoginCallback callback) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("client_id", configuration.googleClientId());
        parameters.put("client_secret", configuration.googleClientSecret());
        parameters.put("code", callback.code());
        parameters.put("code_verifier", callback.attempt().codeVerifier());
        parameters.put("grant_type", "authorization_code");
        parameters.put("redirect_uri", configuration.redirectUri(AuthProviderId.GOOGLE));

        String body = OAuthHttp.postForm(http, URI.create(TOKEN_URL), OAuthHttp.form(parameters))
                .orElse(null);
        if (body == null) {
            Logger.warn("Google token exchange failed");
            return Optional.empty();
        }
        JsonObject response = GSON.fromJson(body, JsonObject.class);
        if (response == null || !response.has("id_token")) {
            Logger.warn("Google token response carried no id_token");
            return Optional.empty();
        }
        return idTokens.verify(response.get("id_token").getAsString(), callback.attempt().nonce());
    }
}
