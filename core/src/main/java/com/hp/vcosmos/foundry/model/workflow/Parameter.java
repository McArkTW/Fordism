package com.hp.vcosmos.foundry.model.workflow;

/**
 * One run parameter. A workflow may declare these as bare names, in which case everything but
 * {@code name} takes its default and the run form draws a plain text box; declaring the rest is
 * what lets the form label the field, mark it required, and prefill it.
 *
 * <p>{@code type} is {@code text}, {@code textarea} or {@code number} — enough to choose a control,
 * and deliberately not a type system.
 */
public record Parameter(String name, String label, String type, boolean required,
                        String defaultValue, String help) {

    public static Parameter named(String name) {
        return new Parameter(name, "", "text", false, "", "");
    }

    /** The label to show, falling back to the name so a bare declaration still reads sensibly. */
    public String labelOrName() {
        return label == null || label.isBlank() ? name : label;
    }
}
