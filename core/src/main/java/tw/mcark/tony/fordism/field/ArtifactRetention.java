package tw.mcark.tony.fordism.field;

import tw.mcark.tony.fordism.workspace.ArtifactStore;
import java.time.Duration;

/**
 * How long a run's {@code artifact/} outlives the run, and where it is kept.
 *
 * <p>A zero (or absent) window keeps every artifact, which is what a workspace has always had: the
 * product is the thing a run was for, so deleting it is an operator's decision, not a default.
 */
public record ArtifactRetention(ArtifactStore store, Duration window) {

    public ArtifactRetention {
        window = window == null ? Duration.ZERO : window;
    }

    /** Nothing is ever culled. */
    public static ArtifactRetention keepForever() {
        return new ArtifactRetention(null, Duration.ZERO);
    }

    public boolean culls() {
        return store != null && window.isPositive();
    }
}
