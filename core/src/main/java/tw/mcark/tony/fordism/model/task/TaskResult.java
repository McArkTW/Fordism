package tw.mcark.tony.fordism.model.task;

import java.util.List;

/**
 * The sensor read of a task's result/result.json. {@code question} is set when state=asked;
 * {@code secrets} names the environment variables that pause needs supplied (values never appear
 * here — the agent asks for names, the human fills them in out of band).
 *
 * <p>{@code verdict} is the one-word answer a gate, a branch predicate or a reconciler loop reads
 * — {@code pass} / {@code fail} / {@code done} / whatever the workflow's {@code when:} compares
 * against. It exists so control flow stops depending on a substring of English prose.
 *
 * <p>{@code childRuns} are the runs the agent asked the engine to start. Only the reconciler
 * strategy acts on them; every other orchestrator ignores the field, so an agent that writes it
 * under a linear workflow has asked for nothing rather than silently forked the instance.
 */
public record TaskResult(ReportedState state, String summary, String question, List<String> secrets,
                         String verdict, List<ChildRunRequest> childRuns) {}
