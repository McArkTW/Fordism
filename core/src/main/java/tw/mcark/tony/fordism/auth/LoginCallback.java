package tw.mcark.tony.fordism.auth;

/** A provider's callback: the authorization code it handed back, and the attempt it belongs to. */
public record LoginCallback(String code, LoginAttempt attempt) {}
