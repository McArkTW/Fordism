package tw.mcark.tony.fordism.orchestrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import tw.mcark.tony.fordism.model.workflow.Strategy;
import org.junit.jupiter.api.Test;

/** Strategy is the list of strategies — the registry and the parser both read it. */
class OrchestratorRegistryTest {
    private static final Gson GSON = new Gson();

    @Test
    void every_strategy_has_an_orchestrator() {
        // The constructor refuses to build an incomplete registry, so this both checks the map and
        // documents why get() can no longer answer null.
        OrchestratorRegistry registry = new OrchestratorRegistry();
        for (Strategy strategy : Strategy.values()) {
            assertNotNull(registry.get(strategy), strategy.wireName());
        }
    }

    @Test
    void the_wire_names_are_what_workflow_yaml_and_the_snapshot_say() {
        assertEquals(Strategy.MAP_REDUCE, Strategy.from("map-reduce").orElseThrow());
        assertEquals("map-reduce", Strategy.MAP_REDUCE.wireName());
        for (Strategy strategy : Strategy.values()) {
            assertEquals("\"" + strategy.wireName() + "\"", GSON.toJson(strategy));
            assertEquals(strategy, GSON.fromJson("\"" + strategy.wireName() + "\"", Strategy.class));
        }
    }

    @Test
    void an_unknown_strategy_is_empty_rather_than_a_default() {
        assertTrue(Strategy.from("sequential").isEmpty());
        assertTrue(Strategy.from(null).isEmpty());
        assertTrue(Strategy.names().contains("linear"));
        assertEquals(6, Strategy.names().size());
    }
}
