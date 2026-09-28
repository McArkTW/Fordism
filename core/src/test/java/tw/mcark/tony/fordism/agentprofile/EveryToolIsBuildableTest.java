package tw.mcark.tony.fordism.agentprofile;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * An {@link AgentTool} is a promise that a task naming it will run. Three things have to line up
 * for that, and they live in three different files, so nothing but this notices when one is
 * missing: the enum entry, an image recipe at {@code agent/tools/<wire>.Dockerfile}, and a branch
 * in the entrypoint that knows how to drive the CLI.
 *
 * <p>The failure when they do not line up is silent and expensive. A missing Dockerfile means
 * {@code docker create} fails on an image nobody built; a missing entrypoint branch is worse,
 * because the container starts, falls through to the {@code *)} default, and runs {@code claude}
 * — which is not installed in that tool's image — so the task produces no result and is reaped as
 * rotten. Neither says "you forgot to add the tool".
 */
class EveryToolIsBuildableTest {

    /** The repo root, found from the working directory the test is run in (core/). */
    private static Path repo() {
        Path here = Path.of("").toAbsolutePath();
        return Files.isDirectory(here.resolve("agent")) ? here : here.getParent();
    }

    @Test
    void every_tool_has_an_image_recipe() {
        List<String> missing = new ArrayList<>();
        for (AgentTool tool : AgentTool.values()) {
            Path dockerfile = repo().resolve("agent/tools/" + tool.wireName() + ".Dockerfile");
            if (!Files.isRegularFile(dockerfile)) {
                missing.add(tool.wireName());
            }
        }
        assertTrue(missing.isEmpty(), "no agent/tools/<tool>.Dockerfile for: " + missing);
    }

    @Test
    void every_image_recipe_has_a_tool() throws IOException {
        List<String> orphans = new ArrayList<>();
        try (Stream<Path> listing = Files.list(repo().resolve("agent/tools"))) {
            for (Path file : (Iterable<Path>) listing.filter(p -> p.toString().endsWith(".Dockerfile"))::iterator) {
                String wire = file.getFileName().toString().replace(".Dockerfile", "");
                boolean known = false;
                for (AgentTool tool : AgentTool.values()) {
                    known = known || tool.wireName().equals(wire);
                }
                if (!known) {
                    orphans.add(wire);
                }
            }
        }
        assertTrue(orphans.isEmpty(), "an image nothing can select: " + orphans);
    }

    /**
     * The expensive one. A tool with no branch falls through to the default, which runs claude in
     * an image that does not have it.
     */
    @Test
    void every_tool_has_a_branch_in_the_entrypoint() throws IOException {
        String entrypoint = Files.readString(repo().resolve("agent/entrypoint.sh"));
        List<String> missing = new ArrayList<>();
        for (AgentTool tool : AgentTool.values()) {
            if (tool == AgentTool.CLAUDE_CODE) {
                continue;   // deliberately the default branch, in an image that does have claude
            }
            if (!entrypoint.contains(tool.wireName() + ")")) {
                missing.add(tool.wireName());
            }
        }
        assertTrue(missing.isEmpty(), "no entrypoint branch for: " + missing);
    }

    /**
     * Every tool ships as its own compose service, or there is no way to build its image without
     * knowing the docker command by heart.
     */
    @Test
    void every_tool_has_a_compose_service() throws IOException {
        String compose = Files.readString(repo().resolve("docker-compose.yml"));
        String build = Files.readString(repo().resolve("docker-compose.override.yml"));
        List<String> missing = new ArrayList<>();
        for (AgentTool tool : AgentTool.values()) {
            String service = "fordism-agent-" + tool.wireName() + ":";
            if (!compose.contains(service) || !build.contains(service)) {
                missing.add(tool.wireName());
            }
        }
        assertTrue(missing.isEmpty(), "no compose service for: " + missing);
    }
}
