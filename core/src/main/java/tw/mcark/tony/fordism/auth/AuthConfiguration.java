package tw.mcark.tony.fordism.auth;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.tinylog.Logger;

/**
 * Authentication settings, read from the environment. Auth is always on: an install with zero
 * enabled providers could never be logged into, so {@link #from(Map)} refuses to start one.
 */
public record AuthConfiguration(
        boolean localEnabled,
        String googleClientId,
        String googleClientSecret,
        String githubClientId,
        String githubClientSecret,
        String microsoftClientId,
        String microsoftClientSecret,
        String microsoftTenantId,
        Set<String> allowedEmails,
        Set<String> allowedDomains,
        String adminSecret,
        String publicUrl,
        boolean cookieSecure,
        boolean trustProxyHeader) {

    /** An Entra directory (tenant) id: a GUID, and nothing else — see {@link #requireTenantDirectoryId}. */
    private static final Pattern DIRECTORY_ID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public static AuthConfiguration fromEnvironment() {
        return from(System.getenv());
    }

    /** Parse and validate; the map seam exists so tests can drive validation without env vars. */
    public static AuthConfiguration from(Map<String, String> environment) {
        String googleClientId = read(environment, "FORDISM_AUTH_GOOGLE_CLIENT_ID");
        String googleClientSecret = read(environment, "FORDISM_AUTH_GOOGLE_CLIENT_SECRET");
        String githubClientId = read(environment, "FORDISM_AUTH_GITHUB_CLIENT_ID");
        String githubClientSecret = read(environment, "FORDISM_AUTH_GITHUB_CLIENT_SECRET");
        String microsoftClientId = read(environment, "FORDISM_AUTH_MICROSOFT_CLIENT_ID");
        String microsoftClientSecret = read(environment, "FORDISM_AUTH_MICROSOFT_CLIENT_SECRET");
        String microsoftTenantId = read(environment, "FORDISM_AUTH_MICROSOFT_TENANT_ID");
        requireBothOrNeither("FORDISM_AUTH_GOOGLE_CLIENT_ID", googleClientId, googleClientSecret);
        requireBothOrNeither("FORDISM_AUTH_GITHUB_CLIENT_ID", githubClientId, githubClientSecret);
        requireBothOrNeither("FORDISM_AUTH_MICROSOFT_CLIENT_ID", microsoftClientId, microsoftClientSecret);
        requireTenantDirectoryId(microsoftClientId, microsoftTenantId);
        AuthConfiguration configuration = new AuthConfiguration(
                Boolean.parseBoolean(read(environment, "FORDISM_AUTH_LOCAL")),
                googleClientId, googleClientSecret, githubClientId, githubClientSecret,
                microsoftClientId, microsoftClientSecret, microsoftTenantId,
                lowercaseSet(read(environment, "FORDISM_AUTH_ALLOWED_EMAILS")),
                lowercaseSet(read(environment, "FORDISM_AUTH_ALLOWED_DOMAINS")),
                adminSecretOrGenerated(read(environment, "FORDISM_ADMIN_SECRET")),
                trimTrailingSlash(read(environment, "FORDISM_PUBLIC_URL")),
                Boolean.parseBoolean(read(environment, "FORDISM_COOKIE_SECURE")),
                Boolean.parseBoolean(read(environment, "FORDISM_TRUSTED_PROXY")));
        if (configuration.providers().isEmpty()) {
            throw new IllegalStateException("no authentication provider is enabled and auth is always on — "
                    + "set FORDISM_AUTH_LOCAL=true, or FORDISM_AUTH_GOOGLE_CLIENT_ID + _SECRET, "
                    + "or FORDISM_AUTH_GITHUB_CLIENT_ID + _SECRET, or FORDISM_AUTH_MICROSOFT_CLIENT_ID "
                    + "+ _SECRET + _TENANT_ID; with zero providers nobody could ever log in");
        }
        return configuration;
    }

    /**
     * The caller's IP for rate-limiting. The socket peer by default; the first hop of
     * {@code X-Forwarded-For} only when a proxy is trusted, because behind one the socket peer is
     * the proxy and every caller would share a single throttle key. Untrusted, that header is
     * attacker-controlled — honoring it would let a guesser forge a fresh key per request and
     * never lock — so it is ignored unless {@code FORDISM_TRUSTED_PROXY} says a proxy sets it.
     */
    public String clientIp(String socketIp, String forwardedFor) {
        if (!trustProxyHeader || forwardedFor == null || forwardedFor.isBlank()) {
            return socketIp;
        }
        int comma = forwardedFor.indexOf(',');
        String first = comma < 0 ? forwardedFor : forwardedFor.substring(0, comma);
        return first.trim().isEmpty() ? socketIp : first.trim();
    }

    public boolean googleEnabled() {
        return !googleClientId.isBlank();
    }

    public boolean githubEnabled() {
        return !githubClientId.isBlank();
    }

    public boolean microsoftEnabled() {
        return !microsoftClientId.isBlank();
    }

    /** The enabled providers, in the order the login screen should offer them. */
    public List<AuthProviderId> providers() {
        List<AuthProviderId> enabled = new ArrayList<>();
        if (localEnabled) {
            enabled.add(AuthProviderId.LOCAL);
        }
        if (googleEnabled()) {
            enabled.add(AuthProviderId.GOOGLE);
        }
        if (githubEnabled()) {
            enabled.add(AuthProviderId.GITHUB);
        }
        if (microsoftEnabled()) {
            enabled.add(AuthProviderId.MICROSOFT);
        }
        return enabled;
    }

    /** The callback URI registered with the provider — built from FORDISM_PUBLIC_URL. */
    public String redirectUri(AuthProviderId provider) {
        return publicUrl + "/api/auth/" + provider.id() + "/callback";
    }

    /**
     * Microsoft sign-in needs the tenant's own directory id, and refuses the multi-tenant endpoints.
     *
     * <p>An Entra id_token's issuer carries the directory id of the tenant that minted it. Pinned to
     * one tenant, that is a constant this instance can check the token against. On {@code common},
     * {@code organizations} or {@code consumers} it is only knowable from the token's own {@code tid}
     * claim — so the check would be the token vouching for itself, and the tenant-scoped JWKS this
     * instance fetches would be the wrong key set anyway.
     *
     * <p>A verified domain name is refused for the same reason: it works as a URL segment, but the
     * issuer it produces is still the directory id, which this instance would then not know.
     */
    private static void requireTenantDirectoryId(String clientId, String tenantId) {
        if (clientId.isBlank()) {
            return;
        }
        if (!DIRECTORY_ID.matcher(tenantId).matches()) {
            throw new IllegalStateException("FORDISM_AUTH_MICROSOFT_TENANT_ID must be the tenant's "
                    + "directory (tenant) id — the GUID on the Entra app registration's Overview page. "
                    + "Got \"" + tenantId + "\". A domain name, or common/organizations/consumers, "
                    + "leaves the id_token issuer unknown to this instance, so it could not be checked");
        }
    }

    private static void requireBothOrNeither(String idVariable, String clientId, String clientSecret) {
        String secretVariable = idVariable.replace("_CLIENT_ID", "_CLIENT_SECRET");
        if (clientId.isBlank() != clientSecret.isBlank()) {
            throw new IllegalStateException("half-configured OAuth provider: " + idVariable + " and "
                    + secretVariable + " must be set together (one of them is blank)");
        }
    }

    private static String adminSecretOrGenerated(String configured) {
        if (!configured.isBlank()) {
            return configured;
        }
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        // Logged once, on purpose: without it a fresh install with no FORDISM_ADMIN_SECRET could
        // never complete the one-time admin bootstrap.
        Logger.warn("FORDISM_ADMIN_SECRET is not set. Generated a bootstrap admin secret for this start:\n\n"
                + "    {}\n\n"
                + "Use it once at POST /api/auth/bootstrap to create the first admin. "
                + "Set FORDISM_ADMIN_SECRET to keep it stable across restarts.", generated);
        return generated;
    }

    private static Set<String> lowercaseSet(String commaSeparated) {
        Set<String> out = new LinkedHashSet<>();
        for (String part : commaSeparated.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(out);
    }

    private static String trimTrailingSlash(String url) {
        String base = url.isBlank() ? "http://localhost" : url.trim();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String read(Map<String, String> environment, String key) {
        String value = environment.get(key);
        return value == null ? "" : value.trim();
    }
}
