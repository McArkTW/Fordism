package tw.mcark.tony.fordism.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tw.mcark.tony.fordism.model.task.Task;
import tw.mcark.tony.fordism.model.task.TaskState;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The model proxy is the one route outside {@code /api}, and therefore the one route the
 * {@code before("/api/*")} auth gate never sees. That is deliberate — its caller is an agent
 * container, which has no session and never will — but it means the per-task token check inside
 * {@link tw.mcark.tony.fordism.proxy.UsageProxy} is the whole of its authorization.
 *
 * <p>{@code RouteAuthorizationTest} proves every gated route rejects the unauthenticated; by
 * construction it cannot cover this one. This is that proof for the ungated route, and it is why
 * the exemption is safe to keep: a caller who cannot name a live task AND present that task's own
 * token gets nothing, and in particular never reaches the provider on the profile's real key.
 */
class ProxyAuthorizationTest {

    @TempDir
    Path stateDir;

    private FordismUnderTest app;

    @BeforeEach
    void start() {
        app = new FordismUnderTest(stateDir);
    }

    @AfterEach
    void stop() {
        app.close();
    }

    private Task liveTask(String token) {
        Task task = new Task("task-live", "run-1", 0, "session-1");
        task.state = TaskState.RUNNING;
        task.proxyToken = token;
        task.agentProfile = "nonexistent-profile";
        app.tasks().save(task);
        return task;
    }

    private int call(String taskId, String token) {
        HttpRequest.Builder request = app.to("/proxy/" + taskId + "/v1/messages")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return app.send(request).status();
    }

    @Test
    void a_call_with_no_token_is_refused() {
        liveTask("the-real-token");
        assertEquals(401, call("task-live", null));
    }

    @Test
    void a_call_with_another_tasks_token_is_refused() {
        liveTask("the-real-token");
        assertEquals(401, call("task-live", "some-other-tasks-token"));
    }

    @Test
    void a_task_that_does_not_exist_is_refused() {
        assertEquals(403, call("no-such-task", "anything"));
    }

    /**
     * A task whose token was never minted — one that was never proxied — must not be reachable by
     * presenting nothing, the empty string, or the word null. {@code presented} returns false for a
     * null token before it compares anything, and this is what holds that in place.
     */
    @Test
    void a_task_with_no_token_minted_cannot_be_reached() {
        liveTask(null);
        assertEquals(401, call("task-live", null));
        assertEquals(401, call("task-live", ""));
        assertEquals(401, call("task-live", "null"));
    }

    @Test
    void a_finished_task_stops_answering_even_with_the_right_token() {
        Task task = liveTask("the-real-token");
        task.state = TaskState.COLLECTED;
        app.tasks().save(task);

        assertEquals(403, call("task-live", "the-real-token"));
    }

    /**
     * The right token gets past authorization — so the refusals above are refusals, not a route
     * that answers nobody. It still cannot reach a provider (the profile does not exist), which
     * fails at the routing step, well past the check this test is about.
     */
    @Test
    void the_tasks_own_token_gets_past_the_check() {
        liveTask("the-real-token");

        int status = call("task-live", "the-real-token");
        assertNotEquals(401, status, "the task's own token was refused");
        assertNotEquals(403, status, "the task's own token was refused");
        assertTrue(status >= 500, "expected a routing failure past the token check, got " + status);
    }
}
