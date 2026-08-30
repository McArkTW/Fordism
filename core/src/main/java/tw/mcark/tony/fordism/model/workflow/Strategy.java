package tw.mcark.tony.fordism.model.workflow;

import com.google.gson.annotations.SerializedName;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * How a workflow's steps are advanced — the fixed set of six the engine knows.
 *
 * <p>This used to be a String validated against one set in the loader and looked up in another map
 * in the registry, which is two lists that had to agree. The enum is the list; the registry maps
 * from it, and an unknown strategy is refused at parse with the valid names in the message.
 *
 * <p>The lowercase wire names are what workflow YAML says and what the run snapshot stores.
 */
public enum Strategy {
    @SerializedName("linear") LINEAR("linear"),
    @SerializedName("graph") GRAPH("graph"),
    @SerializedName("map-reduce") MAP_REDUCE("map-reduce"),
    @SerializedName("conditional") CONDITIONAL("conditional"),
    @SerializedName("rework") REWORK("rework"),
    @SerializedName("reconciler") RECONCILER("reconciler");

    private final String wireName;

    Strategy(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<Strategy> from(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String trimmed = token.trim();
        return Arrays.stream(values()).filter(strategy -> strategy.wireName.equals(trimmed)).findFirst();
    }

    /** The valid names, sorted — for the error a bad workflow gets at parse time. */
    public static List<String> names() {
        return Arrays.stream(values()).map(Strategy::wireName).sorted().toList();
    }
}
