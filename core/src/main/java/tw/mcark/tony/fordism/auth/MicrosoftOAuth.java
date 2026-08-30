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
 * Microsoft sign-in (Entra ID): authorization code + PKCE, then the {@code id_token} from the token
 * response, verified locally against the tenant's published keys.
 *
 * <p>Closer to {@link GoogleOAuth} than to {@link GitHubOAuth} — both are OpenID Connect, so the
 * identity rests on a signature this server checked rather than on a response it merely received,
 * and both share {@link IdTokenVerifier} for that.
 *
 * <p>Where it differs is the address. Entra mints no {@code email_verified}, because in a single
 * tenant there is nothing to verify: the directory issued the account and the token says which
 * directory. {@code email} is an optional claim an administrator may not have turned on, so
 * {@code preferred_username} — the UPN — is the fallback, and it is accepted only when it actually
 * looks like an address. A UPN that is not one belongs to an account this instance cannot key on,
 * and enrolling it would put a non-address into the allowlist comparison.
 */
public final class MicrosoftOAuth implements ExternalAuthProvider {
    private static final String BASE_URL = "https://login.microsoftonline.com/";
    private static final Gson GSON = new Gson();

    private final AuthConfiguration configuration;
    private final HttpClient http;
    private final IdTokenVerifier idTokens;

    public MicrosoftOAuth(AuthConfiguration configuration, HttpClient http, IdTokenVerifier idTokens) {
        this.configuration = configuration;
        this.http = http;
        this.idTokens = idTokens;
    }

    @Override
    public String authorizationUrl(LoginAttempt attempt) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("client_id", configuration.microsoftClientId());
        parameters.put("redirect_uri", configuration.redirectUri(AuthProviderId.MICROSOFT));
        parameters.put("response_type", "code");
        parameters.put("response_mode", "query");
        parameters.put("scope", "openid email profile");
        parameters.put("state", attempt.state());
        parameters.put("nonce", attempt.nonce());
        parameters.put("code_challenge", attempt.codeChallenge());
        parameters.put("code_challenge_method", "S256");
        // Entra remembers a previous consent, so a second sign-in is one redirect with no clicks.
        parameters.put("prompt", "select_account");
        return BASE_URL + configuration.microsoftTenantId() + "/oauth2/v2.0/authorize?"
                + OAuthHttp.form(parameters);
    }

    @Override
    public Optional<ExternalIdentity> identify(LoginCallback callback) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("client_id", configuration.microsoftClientId());
        parameters.put("client_secret", configuration.microsoftClientSecret());
        parameters.put("code", callback.code());
        parameters.put("code_verifier", callback.attempt().codeVerifier());
        parameters.put("grant_type", "authorization_code");
        parameters.put("redirect_uri", configuration.redirectUri(AuthProviderId.MICROSOFT));
        parameters.put("scope", "openid email profile");

        URI tokenUrl = URI.create(BASE_URL + configuration.microsoftTenantId() + "/oauth2/v2.0/token");
        String body = OAuthHttp.postForm(http, tokenUrl, OAuthHttp.form(parameters)).orElse(null);
        if (body == null) {
            Logger.warn("Microsoft token exchange failed");
            return Optional.empty();
        }
        JsonObject response = GSON.fromJson(body, JsonObject.class);
        if (response == null || !response.has("id_token")) {
            Logger.warn("Microsoft token response carried no id_token");
            return Optional.empty();
        }
        return idTokens.verify(response.get("id_token").getAsString(), callback.attempt().nonce())
                .flatMap(MicrosoftOAuth::identityFrom);
    }

    static Optional<ExternalIdentity> identityFrom(VerifiedClaims claims) {
        String address = claims.email().isBlank() ? claims.preferredUsername() : claims.email();
        if (!looksLikeAnAddress(address)) {
            Logger.warn("Microsoft id_token carries no usable address (email and preferred_username "
                    + "are both absent or are not addresses)");
            return Optional.empty();
        }
        String name = claims.displayName().isBlank() ? address : claims.displayName();
        return Optional.of(new ExternalIdentity(AuthProviderId.MICROSOFT, claims.subject(), address, name));
    }

    /**
     * Enough of an address to key an account on. Not a validator — {@link Enrollment} compares the
     * domain against the allowlist, and a value with no single {@code @} would compare as nonsense
     * rather than be refused.
     */
    private static boolean looksLikeAnAddress(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        int at = value.indexOf('@');
        return at > 0 && at == value.lastIndexOf('@') && at < value.length() - 1;
    }
}
