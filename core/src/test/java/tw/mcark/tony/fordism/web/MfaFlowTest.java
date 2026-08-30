package tw.mcark.tony.fordism.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import tw.mcark.tony.fordism.auth.PasswordHash;
import tw.mcark.tony.fordism.auth.Totp;
import tw.mcark.tony.fordism.auth.User;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The second factor through the real API: enrol, then a password alone no longer signs in, a code
 * does, and a recovery code does once. Also that enabling it left an audit line an admin can read.
 */
class MfaFlowTest {
    private static final Gson GSON = new Gson();

    @TempDir
    Path stateDir;

    private FordismUnderTest app;
    private String cookie;

    @BeforeEach
    void start() {
        app = new FordismUnderTest(stateDir);
        // A local account in admins, so it can also read the audit log.
        User user = app.accounts().users().create(User.withPassword("dana@example.com", "Dana",
                PasswordHash.of("a-good-password")));
        app.accounts().groups().update(
                app.accounts().groups().findByName("admins").orElseThrow().withMember(user.id()));
        cookie = "fordism_session=" + app.accounts().sessions().create(user.id()).token();
    }

    @AfterEach
    void stop() {
        app.close();
    }

    @Test
    void enrolling_makes_a_password_alone_insufficient_and_a_code_sufficient() {
        String secret = enrol();

        // Password alone: the server now asks for the second factor.
        FordismUnderTest.Response noCode = login("a-good-password", null, null);
        assertEquals(401, noCode.status());
        assertTrue(noCode.body().contains("mfaRequired"), noCode.body());

        // Password + a valid code: in.
        FordismUnderTest.Response withCode = login("a-good-password", currentCode(secret), null);
        assertEquals(200, withCode.status(), withCode.body());
    }

    @Test
    void a_wrong_code_is_refused_and_a_wrong_password_never_reveals_mfa_is_on() {
        enrol();
        assertEquals(401, login("a-good-password", "000000", null).status());
        // A wrong password gets the same generic answer whether or not MFA is on — no "mfaRequired".
        FordismUnderTest.Response badPass = login("wrong-password", null, null);
        assertEquals(401, badPass.status());
        assertFalse(badPass.body().contains("mfaRequired"), badPass.body());
    }

    @Test
    void a_recovery_code_signs_in_once_and_not_twice() {
        String secret = enrol();
        String recovery = firstRecoveryCode(secret);

        assertEquals(200, login("a-good-password", null, recovery).status(), "recovery works once");
        assertEquals(401, login("a-good-password", null, recovery).status(), "and not again");
    }

    @Test
    void enabling_the_factor_is_written_to_the_audit_log() {
        enrol();
        String audit = app.send(app.to("/api/audit").GET().header("Cookie", cookie)).body();
        assertTrue(audit.contains("enable second factor"), audit);
    }

    @Test
    void an_enrolment_write_without_the_request_header_is_refused() {
        // /api/auth/* is exempt from the gate, so these handlers demand the CSRF header themselves.
        assertEquals(403, app.send(app.to("/api/auth/mfa/begin")
                .header("Cookie", cookie)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))).status());
    }

    // ---- helpers ----

    /** Run begin+confirm and return the TOTP secret now enrolled. */
    private String enrol() {
        JsonObject begun = GSON.fromJson(post("/api/auth/mfa/begin", "{}").body(), JsonObject.class);
        String secret = begun.get("secret").getAsString();
        assertTrue(begun.get("otpauthUri").getAsString().startsWith("otpauth://"));
        FordismUnderTest.Response confirmed = post("/api/auth/mfa/confirm",
                "{\"code\":\"" + currentCode(secret) + "\"}");
        assertEquals(200, confirmed.status(), confirmed.body());
        return secret;
    }

    private String firstRecoveryCode(String secret) {
        // Re-enrolling is not allowed while one is active, so read the codes from the confirm that
        // enabled it instead: redo enrolment on a fresh account would be simpler, but this account
        // is the one in admins. Disable then re-enrol to capture the codes.
        post("/api/auth/mfa/disable", "{\"password\":\"a-good-password\"}");
        JsonObject begun = GSON.fromJson(post("/api/auth/mfa/begin", "{}").body(), JsonObject.class);
        String fresh = begun.get("secret").getAsString();
        JsonObject confirmed = GSON.fromJson(
                post("/api/auth/mfa/confirm", "{\"code\":\"" + currentCode(fresh) + "\"}").body(),
                JsonObject.class);
        return confirmed.getAsJsonArray("recoveryCodes").get(0).getAsString();
    }

    private FordismUnderTest.Response post(String path, String body) {
        return app.send(FordismUnderTest.writing(app.to(path)
                .header("Cookie", cookie)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))));
    }

    private FordismUnderTest.Response login(String password, String code, String recovery) {
        StringBuilder body = new StringBuilder("{\"email\":\"dana@example.com\",\"password\":\"" + password + "\"");
        if (code != null) {
            body.append(",\"code\":\"").append(code).append("\"");
        }
        if (recovery != null) {
            body.append(",\"recoveryCode\":\"").append(recovery).append("\"");
        }
        body.append("}");
        return app.send(FordismUnderTest.writing(app.to("/api/auth/login")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))));
    }

    private static String currentCode(String secret) {
        for (int guess = 0; guess < 1_000_000; guess++) {
            String candidate = String.format("%06d", guess);
            if (Totp.verify(secret, candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("no current code matched the secret");
    }
}
