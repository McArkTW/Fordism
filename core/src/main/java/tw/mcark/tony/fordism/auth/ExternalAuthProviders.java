package tw.mcark.tony.fordism.auth;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the OAuth providers this instance has credentials for. Called once, from the app. */
public final class ExternalAuthProviders {

    private ExternalAuthProviders() {}

    /** Keyed by id, in the order the login screen offers them; empty when only local is enabled. */
    public static Map<AuthProviderId, ExternalAuthProvider> from(AuthConfiguration configuration) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        Map<AuthProviderId, ExternalAuthProvider> providers = new LinkedHashMap<>();
        if (configuration.googleEnabled()) {
            providers.put(AuthProviderId.GOOGLE, new GoogleOAuth(configuration, http,
                    new GoogleIdTokenVerifier(new GoogleJsonWebKeys(http), configuration.googleClientId())));
        }
        if (configuration.githubEnabled()) {
            providers.put(AuthProviderId.GITHUB, new GitHubOAuth(configuration, http));
        }
        return providers;
    }
}
