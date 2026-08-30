package tw.mcark.tony.fordism.launch;

import tw.mcark.tony.fordism.model.task.Task;
import java.io.IOException;

/** Launches / inspects / removes the per-task agent container. */
public interface ContainerLauncher {
    String launch(Task task) throws IOException;
    boolean isRunning(String containerId) throws IOException;
    void kill(String containerId);
    void remove(String containerId);
}
