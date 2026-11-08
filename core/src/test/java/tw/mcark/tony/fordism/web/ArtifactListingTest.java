package tw.mcark.tony.fordism.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The run page's view of the artifact: bulk folders named but not entered, and a hard cap. */
class ArtifactListingTest {

    @TempDir
    Path root;

    private static List<String> paths(RunController.ArtifactListing listing) {
        return listing.files().stream().map(Views.ArtifactEntry::path).sorted().toList();
    }

    @Test
    void git_and_node_modules_are_listed_but_not_entered() throws IOException {
        Files.createDirectories(root.resolve(".git/objects"));
        Files.writeString(root.resolve(".git/HEAD"), "ref");
        Files.createDirectories(root.resolve("web/node_modules/left-pad"));
        Files.writeString(root.resolve("web/node_modules/left-pad/index.js"), "x");
        Files.writeString(root.resolve("web/app.js"), "app");

        RunController.ArtifactListing listing = RunController.ArtifactListing.of(root, 100);

        assertEquals(List.of(".git", "web", "web/app.js", "web/node_modules"), paths(listing));
        assertFalse(listing.truncated());
        assertEquals(3L, listing.files().stream().filter(entry -> entry.path().equals("web/app.js"))
                .findFirst().orElseThrow().size());
    }

    @Test
    void the_listing_stops_at_the_cap_and_says_so() throws IOException {
        for (int file = 0; file < 10; file++) {
            Files.writeString(root.resolve("f" + file + ".txt"), "x");
        }

        RunController.ArtifactListing listing = RunController.ArtifactListing.of(root, 4);

        assertEquals(4, listing.files().size());
        assertTrue(listing.truncated());
    }

    @Test
    void no_artifact_on_disk_lists_nothing() throws IOException {
        RunController.ArtifactListing listing = RunController.ArtifactListing.of(root.resolve("absent"), 10);

        assertTrue(listing.files().isEmpty());
        assertFalse(listing.truncated());
    }
}
