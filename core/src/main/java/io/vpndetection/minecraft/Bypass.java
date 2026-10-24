package io.vpndetection.minecraft;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * bypass.txt: the players and addresses that are never looked up.
 *
 * <p>One entry per line, a {@code #} starting a comment: a player's name or UUID, an address, or a
 * range. Reads see an immutable snapshot, so a login thread never waits on a command editing the
 * file; the edits themselves are serialized and written through a temporary file, so a crash
 * mid-write never leaves half a list.
 */
final class Bypass {
    private static final Pattern NAME = Pattern.compile("[.*]?[A-Za-z0-9_]{1,24}");
    private static final Pattern UUID_DASHED =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern UUID_PLAIN = Pattern.compile("[0-9a-fA-F]{32}");

    /** One parsed entry, as bypass.txt should spell it. */
    record Entry(String text, UUID uuid, String name, Range range) {
        boolean covers(String player, UUID id, InetAddress address) {
            if (uuid != null) {
                return uuid.equals(id);
            }
            if (name != null) {
                return player != null && name.equals(player.toLowerCase(Locale.ROOT));
            }
            return address != null && range.contains(address);
        }
    }

    private final Path file;
    private volatile List<Entry> entries;

    private Bypass(Path file, List<Entry> entries) {
        this.file = file;
        this.entries = List.copyOf(entries);
    }

    /** A list with nothing in it yet, for a file that could not be read. */
    static Bypass empty(Path file) {
        return new Bypass(file, List.of());
    }

    /** Reads the file, reporting each line it cannot read to {@code problems} and skipping it. */
    static Bypass load(Path file, List<String> problems) throws IOException {
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (Files.exists(file)) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String text = strip(lines.get(i));
                if (text.isEmpty()) {
                    continue;
                }
                Entry entry = parse(text);
                if (entry == null) {
                    problems.add("bypass.txt line " + (i + 1) + ": '" + text
                            + "' is not a player name, UUID, address or range");
                } else if (seen.add(entry.text())) {
                    entries.add(entry);
                }
            }
        }
        return new Bypass(file, entries);
    }

    /** An entry as bypass.txt would hold it, or null when the text is none of the four kinds. */
    static Entry parse(String text) {
        String trimmed = text.strip();
        if (UUID_DASHED.matcher(trimmed).matches()) {
            UUID uuid = UUID.fromString(trimmed);
            return new Entry(uuid.toString(), uuid, null, null);
        }
        if (UUID_PLAIN.matcher(trimmed).matches()) {
            UUID uuid = UUID.fromString(trimmed.replaceFirst(
                    "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
            return new Entry(uuid.toString(), uuid, null, null);
        }
        Range range = Range.parse(trimmed);
        if (range != null) {
            return new Entry(range.text(), null, null, range);
        }
        if (NAME.matcher(trimmed).matches()) {
            String name = trimmed.toLowerCase(Locale.ROOT);
            return new Entry(name, null, name, null);
        }
        return null;
    }

    /** Whether a login is exempt. Any of the three may be null when the caller does not know it yet. */
    boolean covers(String name, UUID uuid, InetAddress address) {
        for (Entry entry : entries) {
            if (entry.covers(name, uuid, address)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the address alone is exempt, before the player is known. */
    boolean coversAddress(InetAddress address) {
        return covers(null, null, address);
    }

    List<Entry> entries() {
        return entries;
    }

    /** Adds an entry with a comment after it. False when an equal entry is there already. */
    synchronized boolean add(Entry entry, String comment) throws IOException {
        if (contains(entry)) {
            return false;
        }
        List<String> lines = Files.exists(file)
                ? Files.readAllLines(file, StandardCharsets.UTF_8) : new ArrayList<>();
        boolean bare = comment == null || comment.isBlank();
        lines.add(bare ? entry.text() : entry.text() + "  # " + comment.strip());
        write(lines);
        List<Entry> next = new ArrayList<>(entries);
        next.add(entry);
        entries = List.copyOf(next);
        return true;
    }

    /** Removes every line holding this entry. False when none did. */
    synchronized boolean remove(Entry entry) throws IOException {
        if (!contains(entry)) {
            return false;
        }
        List<String> kept = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Entry parsed = parse(strip(line));
            if (parsed == null || !parsed.text().equals(entry.text())) {
                kept.add(line);
            }
        }
        write(kept);
        entries = entries.stream().filter(e -> !e.text().equals(entry.text())).toList();
        return true;
    }

    private boolean contains(Entry entry) {
        return entries.stream().anyMatch(e -> e.text().equals(entry.text()));
    }

    private void write(List<String> lines) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, lines, StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String strip(String line) {
        int hash = line.indexOf('#');
        return (hash >= 0 ? line.substring(0, hash) : line).strip();
    }
}
