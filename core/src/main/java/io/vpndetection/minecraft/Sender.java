package io.vpndetection.minecraft;

/** Whoever ran a command: a player or the console. Safe to use from any thread. */
public interface Sender {
    boolean hasPermission(String permission);

    /** Sends a line, already colored. */
    void send(String text);

    /** Sends a line, already colored, that opens {@code url} when a player clicks it. */
    void sendLink(String text, String url);
}
