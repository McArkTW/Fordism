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
                    new IdTokenVerifier(RemoteJsonWebKeys.google(http),
                            IdTokenExpectation.google(configuration.googleClientId()))));
        }
        if (configuration.githubEnabled()) {
            providers.put(AuthProviderId.GITHUB, new GitHubOAuth(configuration, http));
        }
        // One key cache and one expectation per provider: two issuers can publish keys under the
        // same id, so a shared cache would let one issuer's key verify the other's tokens.
        if (configuration.microsoftEnabled()) {
            providers.put(AuthProviderId.MICROSOFT, new MicrosoftOAuth(configuration, http,
                    new IdTokenVerifier(
                            RemoteJsonWebKeys.microsoft(http, configuration.microsoftTenantId()),
                            IdTokenExpectation.microsoft(configuration.microsoftTenantId(),
                                    configuration.microsoftClientId()))));
        }
        return providers;
    }
}
