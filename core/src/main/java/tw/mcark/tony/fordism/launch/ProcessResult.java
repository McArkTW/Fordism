package tw.mcark.tony.fordism.launch;

/** The outcome of a shelled process. */
public record ProcessResult(int exit, String out, String err) {}
