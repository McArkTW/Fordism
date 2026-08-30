package tw.mcark.tony.fordism.orchestrate;

import tw.mcark.tony.fordism.model.workflow.Strategy;
import tw.mcark.tony.fordism.orchestrate.strategy.ConditionalOrchestrator;
import tw.mcark.tony.fordism.orchestrate.strategy.GraphOrchestrator;
import tw.mcark.tony.fordism.orchestrate.strategy.LinearOrchestrator;
import tw.mcark.tony.fordism.orchestrate.strategy.MapReduceOrchestrator;
import tw.mcark.tony.fordism.orchestrate.strategy.ReconcilerOrchestrator;
import tw.mcark.tony.fordism.orchestrate.strategy.ReworkOrchestrator;
import java.util.EnumMap;
import java.util.Map;

/**
 * The orchestrator each strategy runs with.
 *
 * <p>Keyed by the enum rather than a string, so the compiler — not a lookup that can miss — is what
 * says every strategy has one. {@link #get} returning null was how a workflow whose strategy nobody
 * had implemented failed its run at the first tick; that cannot arise now.
 */
public final class OrchestratorRegistry {
    private final Map<Strategy, Orchestrator> byStrategy = new EnumMap<>(Strategy.class);

    public OrchestratorRegistry() {
        byStrategy.put(Strategy.LINEAR, new LinearOrchestrator());
        byStrategy.put(Strategy.REWORK, new ReworkOrchestrator());
        byStrategy.put(Strategy.GRAPH, new GraphOrchestrator());
        byStrategy.put(Strategy.MAP_REDUCE, new MapReduceOrchestrator());
        byStrategy.put(Strategy.CONDITIONAL, new ConditionalOrchestrator());
        byStrategy.put(Strategy.RECONCILER, new ReconcilerOrchestrator());
        if (byStrategy.size() != Strategy.values().length) {
            throw new IllegalStateException("no orchestrator registered for every strategy");
        }
    }

    public Orchestrator get(Strategy strategy) {
        return byStrategy.get(strategy);
    }
}
