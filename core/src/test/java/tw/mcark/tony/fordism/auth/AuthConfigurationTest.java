package tw.mcark.tony.fordism.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What the environment is allowed to say about sign-in.
 *
 * <p>Every refusal here fails the boot rather than the first login. A half-configured provider, or
 * an install with no provider at all, is a thing you want to learn about from a container that will
 * not start — not from an operator who cannot get in and has no idea why.
 */
class AuthConfigurationTest {

    private static final String TENANT = "72f988bf-86f1-41af-91ab-2d7cd011db47";

    /** The environment, built one variable at a time — from() takes a map so this needs no env. */
    private static Map<String, String> environment(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return out;
    }

    @Test
    void an_install_with_no_provider_at_all_refuses_to_start() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> AuthConfiguration.from(environment()));
        assertTrue(thrown.getMessage().contains("FORDISM_AUTH_LOCAL"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("MICROSOFT"), thrown.getMessage());
    }

    @Test
    void microsoft_needs_its_secret_as_well_as_its_client_id() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> AuthConfiguration.from(environment(
                        "FORDISM_AUTH_MICROSOFT_CLIENT_ID", "an-app-registration",
                        "FORDISM_AUTH_MICROSOFT_TENANT_ID", TENANT)));
        assertTrue(thrown.getMessage().contains("FORDISM_AUTH_MICROSOFT_CLIENT_SECRET"), thrown.getMessage());
    }

    @Test
    void microsoft_is_offered_once_it_is_fully_configured() {
        AuthConfiguration configuration = AuthConfiguration.from(environment(
                "FORDISM_AUTH_MICROSOFT_CLIENT_ID", "an-app-registration",
                "FORDISM_AUTH_MICROSOFT_CLIENT_SECRET", "a-client-secret",
                "FORDISM_AUTH_MICROSOFT_TENANT_ID", TENANT));
        assertTrue(configuration.microsoftEnabled());
        assertEquals(List.of(AuthProviderId.MICROSOFT), configuration.providers());
        assertEquals("http://localhost/api/auth/microsoft/callback",
                configuration.redirectUri(AuthProviderId.MICROSOFT));
    }

    @Test
    void the_multi_tenant_endpoints_are_refused_with_the_reason() {
        // On common/organizations/consumers the id_token's issuer is only knowable from the token's
        // own tid claim, so checking it would be the token vouching for itself.
        for (String tenant : List.of("common", "organizations", "consumers", "contoso.com", "")) {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> AuthConfiguration.from(environment(
                            "FORDISM_AUTH_MICROSOFT_CLIENT_ID", "an-app-registration",
                            "FORDISM_AUTH_MICROSOFT_CLIENT_SECRET", "a-client-secret",
                            "FORDISM_AUTH_MICROSOFT_TENANT_ID", tenant)),
                    "tenant \"" + tenant + "\" must be refused");
            assertTrue(thrown.getMessage().contains("directory"), thrown.getMessage());
        }
    }

    @Test
    void a_tenant_id_is_only_demanded_when_microsoft_sign_in_is_actually_on() {
        AuthConfiguration configuration = AuthConfiguration.from(
                environment("FORDISM_AUTH_LOCAL", "true"));
        assertFalse(configuration.microsoftEnabled());
        assertEquals(List.of(AuthProviderId.LOCAL), configuration.providers());
    }

    @Test
    void the_login_screen_is_offered_the_providers_in_a_fixed_order() {
        AuthConfiguration configuration = AuthConfiguration.from(environment(
                "FORDISM_AUTH_LOCAL", "true",
                "FORDISM_AUTH_GOOGLE_CLIENT_ID", "g", "FORDISM_AUTH_GOOGLE_CLIENT_SECRET", "gs",
                "FORDISM_AUTH_GITHUB_CLIENT_ID", "h", "FORDISM_AUTH_GITHUB_CLIENT_SECRET", "hs",
                "FORDISM_AUTH_MICROSOFT_CLIENT_ID", "m", "FORDISM_AUTH_MICROSOFT_CLIENT_SECRET", "ms",
                "FORDISM_AUTH_MICROSOFT_TENANT_ID", TENANT));
        assertEquals(List.of(AuthProviderId.LOCAL, AuthProviderId.GOOGLE, AuthProviderId.GITHUB,
                AuthProviderId.MICROSOFT), configuration.providers());
    }
}
