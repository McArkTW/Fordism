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
 * Verifies a Heimdall identity token (RS256) with Heimdall's public key — no
 * dependency, no call back to Heimdall. The browser gets the token via PKCE and
 * sends it as a Bearer; this proves who the user is.
 */
public final class HeimdallAuth {
    private static final Gson GSON = new Gson();
    private final RSAPublicKey key;

    public HeimdallAuth(String pem) {
        try {
            String b64 = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                            .replace("-----END PUBLIC KEY-----", "")
                            .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(b64);
            this.key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("bad Heimdall public key (HEIMDALL_PUBKEY)", e);
        }
    }

    /** The email if the token is a valid, unexpired Heimdall identity token; else null. */
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
            if (!claims.has("iss") || !"heimdall.local".equals(claims.get("iss").getAsString())) {
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
