package tw.mcark.tony.fordism.parse;

import java.util.Optional;

/**
 * Resolves a step's template to the agent it runs as — what the loader needs to refuse a
 * {@code continues} step whose session would belong to a different CLI.
 *
 * <p>An interface rather than the template store itself, so the loader stays a parser: it is handed
 * this, it never reads the disk.
 */
public interface StepAgents {

    /** Resolves nothing: every template is "not known yet", so no profile check can fail. */
    StepAgents NONE = new StepAgents() {
        public Optional<StepAgent> of(String template) {
            return Optional.empty();
        }

        public Optional<StepAgent> bare() {
            return Optional.empty();
        }
    };

    /** The agent a step on this template runs as, or empty when no such template exists (yet). */
    Optional<StepAgent> of(String template);

    /**
     * What a step runs as when its template does not exist: the dispatcher runs it bare, on the
     * default backend. Empty when that is not known either.
     */
    Optional<StepAgent> bare();
}
