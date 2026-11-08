package tw.mcark.tony.fordism.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The run's product folder: laid out once, copied — never shared — when a run continues another.
 */
class ArtifactStoreTest {

    @TempDir
    Path root;

    private ArtifactStore store() {
        return new ArtifactStore(root.resolve("_artifacts"), Path.of("/host/_artifacts"), 1001);
    }

    @Test
    void a_run_with_nothing_to_continue_starts_from_an_empty_folder() throws IOException {
        Path artifact = store().prepare("run-1", null);

        assertTrue(Files.isDirectory(artifact));
        try (Stream<Path> entries = Files.list(artifact)) {
            assertEquals(0, entries.count());
        }
        assertEquals(Path.of("/host/_artifacts", "run-1").toString(), store().hostPath("run-1"));
    }

    @Test
    void artifactFrom_copies_the_source_runs_artifact_in() throws IOException {
        ArtifactStore store = store();
        Path source = store.prepare("design", null);
        Files.createDirectories(source.resolve("src/main"));
        Files.writeString(source.resolve("src/main/App.java"), "class App {}");
        Files.writeString(source.resolve("README.md"), "# product");

        Path artifact = store.prepare("execute", "design");

        assertEquals("class App {}", Files.readString(artifact.resolve("src/main/App.java")));
        assertEquals("# product", Files.readString(artifact.resolve("README.md")));
    }

    @Test
    void the_copy_is_the_new_runs_own_and_cannot_change_the_source() throws IOException {
        ArtifactStore store = store();
        Path source = store.prepare("design", null);
        Files.writeString(source.resolve("plan.md"), "v1");

        Path artifact = store.prepare("execute", "design");
        Files.writeString(artifact.resolve("plan.md"), "v2");

        assertEquals("v1", Files.readString(source.resolve("plan.md")));
    }

    @Test
    void a_later_task_of_the_run_works_on_what_the_earlier_one_left_rather_than_a_fresh_copy() throws IOException {
        ArtifactStore store = store();
        Path source = store.prepare("design", null);
        Files.writeString(source.resolve("plan.md"), "v1");
        Path artifact = store.prepare("execute", "design");
        Files.writeString(artifact.resolve("built.txt"), "step one did this");

        store.prepare("execute", "design");   // the second step stages

        assertEquals("step one did this", Files.readString(artifact.resolve("built.txt")));
    }

    @Test
    void continuing_from_a_run_whose_artifact_is_gone_fails_with_the_reason() {
        IOException thrown = assertThrows(IOException.class, () -> store().prepare("execute", "culled"));
        assertTrue(thrown.getMessage().contains("culled"), thrown.getMessage());
        assertFalse(store().exists("execute"), "no half-made folder is left to be taken for a copy");
    }

    @Test
    void a_run_id_cannot_climb_out_of_the_artifacts_folder() {
        assertThrows(IllegalArgumentException.class, () -> store().path("../_credentials"));
        assertFalse(store().exists("../_credentials"));
    }

    @Test
    void two_tasks_of_one_run_staged_at_once_get_one_copy() throws Exception {
        // Map items are staged moments apart; each must find the same artifact, copied once.
        ArtifactStore store = store();
        Path source = store.prepare("design", null);
        Files.writeString(source.resolve("plan.md"), "v1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Path>> staged = new ArrayList<>();
            for (int task = 0; task < 2; task++) {
                staged.add(pool.submit(() -> {
                    go.await();
                    return store.prepare("execute", "design");
                }));
            }
            go.countDown();
            assertEquals(staged.get(0).get(), staged.get(1).get());
        } finally {
            pool.shutdownNow();
        }

        assertEquals("v1", Files.readString(store.path("execute").resolve("plan.md")));
        try (Stream<Path> entries = Files.list(root.resolve("_artifacts"))) {
            assertEquals(List.of("design", "execute"),
                    entries.map(path -> path.getFileName().toString()).sorted().toList(),
                    "one copy, and no staging folder left behind");
        }
    }

    @Test
    void a_copy_that_loses_the_race_is_discarded_and_the_first_one_kept() throws IOException {
        // Another core process (or a stager this lock does not cover) renamed its copy in first.
        Path artifacts = root.resolve("_artifacts");
        Path installed = Files.createDirectories(artifacts.resolve("execute"));
        Files.writeString(installed.resolve("work.txt"), "a task is already in here");
        Path staging = Files.createDirectories(artifacts.resolve(".execute-late"));
        Files.writeString(staging.resolve("work.txt"), "a second copy");

        assertFalse(ArtifactStore.install(staging, installed));

        assertEquals("a task is already in here", Files.readString(installed.resolve("work.txt")));
        assertFalse(Files.exists(staging), "the losing copy is deleted");
    }

    @Test
    void delete_removes_the_tree_and_is_safe_to_repeat() throws IOException {
        ArtifactStore store = store();
        Path artifact = store.prepare("run-1", null);
        Files.createDirectories(artifact.resolve("a/b"));
        Files.writeString(artifact.resolve("a/b/c.txt"), "x");

        store.delete("run-1");
        store.delete("run-1");

        assertFalse(store.exists("run-1"));
    }
}
