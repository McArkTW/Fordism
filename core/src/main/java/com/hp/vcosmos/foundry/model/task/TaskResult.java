package com.hp.vcosmos.foundry.model.task;

import java.util.List;

/**
 * The sensor read of a task's result/result.json. {@code question} is set when state=asked;
 * {@code secrets} names the environment variables that pause needs supplied (values never appear
 * here — the agent asks for names, the human fills them in out of band).
 *
 * <p>{@code verdict} is the one-word answer a gate, a branch predicate or a reconciler loop reads
 * — {@code pass} / {@code fail} / {@code done} / whatever the workflow's {@code when:} compares
 * against. It exists so control flow stops depending on a substring of English prose.
 */
public record TaskResult(ReportedState state, String summary, String question, List<String> secrets,
                         String verdict) {}
