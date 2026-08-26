package com.hp.vcosmos.foundry.credential;

import java.util.List;

/**
 * A credential as the browser sees it. There is no value field and no endpoint that returns one —
 * {@code hasValue} is all the UI needs to tell "set" from "declared but empty". {@code usedBy} is
 * filled in by the controller from the templates that declare the key.
 */
public record CredentialView(String key, String note, boolean hasValue, long updatedAt,
                             List<String> usedBy) {

    public static CredentialView of(Credential credential) {
        return new CredentialView(credential.key(), credential.note() == null ? "" : credential.note(),
                credential.hasValue(), credential.updatedAt(), null);
    }

    public CredentialView usedBy(List<String> templateNames) {
        return new CredentialView(key, note, hasValue, updatedAt, templateNames);
    }
}
