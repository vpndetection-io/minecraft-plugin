package io.vpndetection.minecraft;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** config.yml and bypass.txt on disk: the defaults written on first start, read, and the key saved. */
final class ConfigFile {
    static final String CONFIG = "config.yml";
    static final String BYPASS = "bypass.txt";
    private static final Pattern API_KEY_LINE = Pattern.compile("^api-key\\s*:.*$");

    private ConfigFile() {}

    /** Writes the shipped config.yml and bypass.txt where they do not exist yet. */
    static void writeDefaults(Path dir) throws IOException {
        Files.createDirectories(dir);
        for (String name : List.of(CONFIG, BYPASS)) {
            Path target = dir.resolve(name);
            if (Files.notExists(target)) {
                try (InputStream in = resource(name)) {
                    Files.copy(in, target);
                }
            }
        }
    }

    /** The config.yml shipped in the jar, parsed: every fallback Settings uses. */
    static Map<String, Object> defaults() {
        try (InputStream in = resource(CONFIG)) {
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A config.yml read from disk.
     *
     * @throws IllegalArgumentException naming the line, for a file that is not valid YAML or whose top
     *     level is not a set of settings.
     */
    static Map<String, Object> read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader);
        }
    }

    static Map<String, Object> parse(Object source) {
        Object root;
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            root = source instanceof Reader reader ? yaml.load(reader) : yaml.load((String) source);
        } catch (MarkedYAMLException e) {
            int line = e.getProblemMark().getLine() + 1;
            throw new IllegalArgumentException("line " + line + ": " + e.getProblem(), e);
        } catch (YAMLException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        if (root == null) {
            return Map.of();
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "the file must be a list of settings, like the one VPNDetection wrote");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    /**
     * Puts a key in config.yml's api-key line, leaving every other line, and every comment, as it was.
     * Written through a temporary file, so a crash mid-write never leaves half a config.
     */
    static void saveApiKey(Path file, String key) throws IOException {
        List<String> lines = Files.exists(file)
                ? Files.readAllLines(file, StandardCharsets.UTF_8) : new ArrayList<>();
        String line = "api-key: \"" + key + "\"";
        boolean replaced = false;
        for (int i = 0; i < lines.size() && !replaced; i++) {
            if (API_KEY_LINE.matcher(lines.get(i)).matches()) {
                lines.set(i, line);
                replaced = true;
            }
        }
        if (!replaced) {
            lines.add(0, line);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, lines, StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // Beside this class rather than at the jar's root, where a parent class loader asked first could
    // answer with a config.yml of its own.
    private static InputStream resource(String name) throws IOException {
        InputStream in = ConfigFile.class.getResourceAsStream(name);
        if (in == null) {
            throw new IOException(name + " is missing from the plugin's jar");
        }
        return in;
    }
}
