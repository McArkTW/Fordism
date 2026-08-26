package com.hp.vcosmos.foundry.workspace;

/**
 * One file an agent left under {@code result/}, prepared for preview.
 *
 * <p>{@code content} is empty when {@code binary} — either it holds a NUL byte in its head, or it
 * is too large to inline and the caller should offer the zip instead.
 */
public record ResultFile(String name, long size, boolean binary, String content) {}
