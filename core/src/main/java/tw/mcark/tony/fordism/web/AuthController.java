package tw.mcark.tony.fordism.web;

import com.google.gson.Gson;
import io.javalin.http.Context;

/** /api/auth/me — verify the Bearer identity token and return the user's profile. */
public final class AuthController {
    private static final Gson GSON = new Gson();
    private final HeimdallAuth auth;
    private final UserStore users;

    public AuthController(HeimdallAuth auth, UserStore users) {
        this.auth = auth;
        this.users = users;
    }

    public void me(Context ctx) {
        String email = auth.verify(HeimdallAuth.bearer(ctx));
        if (email == null) {
            ctx.status(401).contentType("application/json").result("{\"error\":\"not logged in\"}");
            return;
        }
        ctx.contentType("application/json").result(GSON.toJson(users.touch(email)));
    }
}
