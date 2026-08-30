package tw.mcark.tony.fordism.orchestrate;

import org.tinylog.Logger;

/** Background reconcile loop: ticks the engine forever. */
public final class ReconcileLoop implements Runnable {
    private final Engine engine;
    private volatile boolean stopped;

    public ReconcileLoop(Engine engine) { this.engine = engine; }

    public void run() {
        while (!stopped) {
            try {
                engine.tick();
                Thread.sleep(engine.reconcileIntervalMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // Not Exception: an Error kills this thread just as dead, and a dead loop means
                // nothing is ever collected, reaped or dispatched again on the whole instance.
                Logger.error(t, "reconcile tick failed");
            }
        }
    }

    public void stop() { stopped = true; }
}
