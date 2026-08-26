package tw.mcark.tony.fordism.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.javalin.http.Context;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Verifies an RS256 identity token (JWT) with the identity provider's public key — no dependency,
 * no callback to the issuer. The browser obtains the token (e.g. via PKCE) and sends it as a
 * Bearer; this proves who the user is.
 *
 * <p>The issuer claim must equal the configured issuer. The audience claim is checked only when an
 * audience is configured — without it, a token minted for any other service at the same issuer
 * would be accepted here as a valid identity.
 */
public final class IdentityTokenAuth {
    private static final Gson GSON = new Gson();
    private final RSAPublicKey key;
    private final String issuer;
    private final String audience;

    public IdentityTokenAuth(AuthSettings settings) {
        try {
            String b64 = settings.pem().replace("-----BEGIN PUBLIC KEY-----", "")
                            .replace("-----END PUBLIC KEY-----", "")
                            .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(b64);
            this.key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("bad identity-provider public key (FORDISM_AUTH_PUBKEY)", e);
        }
        this.issuer = settings.issuer();
        this.audience = settings.audience();
    }

    /** What the environment provides: PEM public key, expected issuer, optional audience. */
    public record AuthSettings(String pem, String issuer, String audience) {}

    /** The email if the token is a valid, unexpired identity token from our issuer; else null. */
    public String verify(String token) {
        if (token == null) {
            return null;
        }
        try {
            String[] p = token.split("\\.");
            if (p.length != 3) {
                return null;
            }
            byte[] signed = (p[0] + "." + p[1]).getBytes(StandardCharsets.US_ASCII);
            Signature v = Signature.getInstance("SHA256withRSA");
            v.initVerify(key);
            v.update(signed);
            if (!v.verify(Base64.getUrlDecoder().decode(p[2]))) {
                return null;
            }
            String json = new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8);
            JsonObject claims = GSON.fromJson(json, JsonObject.class);
            long exp = claims.has("exp") ? claims.get("exp").getAsLong() : 0;
            if (exp < System.currentTimeMillis() / 1000) {
                return null;
            }
            if (!claims.has("iss") || !issuer.equals(claims.get("iss").getAsString())) {
                return null;
            }
            if (!audience.isBlank()
                    && (!claims.has("aud") || !audience.equals(claims.get("aud").getAsString()))) {
                return null;
            }
            return claims.has("email") ? claims.get("email").getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String bearer(Context ctx) {
        String h = ctx.header("Authorization");
        return (h != null && h.startsWith("Bearer ")) ? h.substring(7) : null;
    }
}
