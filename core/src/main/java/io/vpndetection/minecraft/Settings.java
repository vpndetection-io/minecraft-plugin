package io.vpndetection.minecraft;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * config.yml, read and checked.
 *
 * <p>A value that is wrong is reported and replaced by the default config.yml's, entry by entry, so a
 * typo in one check never turns the others off. Every problem goes to {@code problems}, which the
 * caller logs.
 */
final class Settings {
    private static final Set<String> KEYS = Set.of(
            "api-key", "checks", "countries", "rules", "messages", "fail-closed", "cache-minutes");
    private static final Set<String> MESSAGES = Set.of("kick", "kick-country", "failed", "alert", "blocked");
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");
    private static final int MAX_CACHE_MINUTES = 7 * 24 * 60;

    final String apiKey;
    final Map<Check, Action> checks;
    final Set<String> blockCountries;
    final Set<String> allowCountries;
    final List<Rule> rules;
    final Map<String, String> messages;
    final boolean failClosed;
    final Duration cacheTtl;

    private Settings(String apiKey, Map<Check, Action> checks, Set<String> blockCountries,
            Set<String> allowCountries, List<Rule> rules, Map<String, String> messages, boolean failClosed,
            Duration cacheTtl) {
        this.apiKey = apiKey;
        this.checks = Collections.unmodifiableMap(checks);
        this.blockCountries = Collections.unmodifiableSet(blockCountries);
        this.allowCountries = Collections.unmodifiableSet(allowCountries);
        this.rules = List.copyOf(rules);
        this.messages = Map.copyOf(messages);
        this.failClosed = failClosed;
        this.cacheTtl = cacheTtl;
    }

    String message(String name) {
        return messages.get(name);
    }

    /**
     * Reads a parsed config.yml against the defaults shipped in the jar, which are trusted: every
     * fallback comes from there.
     */
    static Settings parse(Map<String, Object> config, Map<String, Object> defaults, List<String> problems) {
        for (String key : config.keySet()) {
            if (!KEYS.contains(key)) {
                problems.add("'" + key + "' is not a setting");
            }
        }
        Read read = new Read(config, defaults, problems);

        String apiKey = read.string("api-key");
        Map<Check, Action> checks = new EnumMap<>(Check.class);
        Map<String, Object> userChecks = read.section("checks");
        for (String key : userChecks.keySet()) {
            if (Check.of(key) == null) {
                problems.add("checks." + key + " is not a check; they are vpn, tor, hosting, proxy and relay");
            }
        }
        Map<String, Object> defaultChecks = Read.map(defaults.get("checks"));
        for (Check check : Check.values()) {
            Object value = userChecks.get(check.key());
            Action action = Action.parse(value);
            if (action == null) {
                if (value != null) {
                    problems.add("checks." + check.key() + " is '" + value + "', not block, alert or allow");
                }
                action = Action.parse(defaultChecks.get(check.key()));
            }
            checks.put(check, action);
        }

        Map<String, Object> countries = read.section("countries");
        Set<String> block = countries(countries.get("block"), "countries.block", problems);
        Set<String> allow = countries(countries.get("allow"), "countries.allow", problems);

        List<Rule> rules = new ArrayList<>();
        Object rawRules = config.containsKey("rules") ? config.get("rules") : defaults.get("rules");
        if (rawRules != null && !(rawRules instanceof List)) {
            problems.add("rules must be a list");
        } else if (rawRules != null) {
            List<?> list = (List<?>) rawRules;
            for (int i = 0; i < list.size(); i++) {
                Rule rule = rule(list.get(i), i + 1, problems);
                if (rule != null) {
                    rules.add(rule);
                }
            }
        }

        Map<String, String> messages = new HashMap<>();
        Map<String, Object> userMessages = read.section("messages");
        Map<String, Object> defaultMessages = Read.map(defaults.get("messages"));
        for (String key : userMessages.keySet()) {
            if (!MESSAGES.contains(key)) {
                problems.add("messages." + key + " is not a message");
            }
        }
        for (String key : MESSAGES) {
            Object value = userMessages.get(key);
            if (value != null && !(value instanceof String)) {
                problems.add("messages." + key + " must be text in quotes");
                value = null;
            }
            messages.put(key, value != null ? (String) value : (String) defaultMessages.get(key));
        }

        boolean failClosed = read.bool("fail-closed");
        int minutes = read.integer("cache-minutes", 0, MAX_CACHE_MINUTES);
        return new Settings(apiKey == null || apiKey.isBlank() ? null : apiKey.strip(), checks, block, allow,
                rules, messages, failClosed, Duration.ofMinutes(minutes));
    }

    private static Set<String> countries(Object raw, String name, List<String> problems) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof List<?> list)) {
            problems.add(name + " must be a list, like [US, DE]");
            return out;
        }
        for (Object entry : list) {
            // YAML 1.1 reads an unquoted NO as false, and Norway's code is NO.
            String code = Boolean.FALSE.equals(entry) ? "NO"
                    : String.valueOf(entry).strip().toUpperCase(Locale.ROOT);
            if (COUNTRY.matcher(code).matches()) {
                out.add(code);
            } else {
                problems.add(name + ": '" + entry + "' is not a two-letter country code");
            }
        }
        return out;
    }

    private static Rule rule(Object raw, int number, List<String> problems) {
        String name = "rules #" + number;
        if (!(raw instanceof Map<?, ?> map)) {
            problems.add(name + " must have an action and a match");
            return null;
        }
        for (Object key : map.keySet()) {
            if (!Set.of("action", "reason", "match").contains(String.valueOf(key))) {
                problems.add(name + ": '" + key + "' is not part of a rule; it has action, reason and match");
            }
        }
        Action action = Action.parse(map.get("action"));
        if (action == null || action == Action.ALLOW) {
            problems.add(name + ": action must be block or alert, so it is skipped");
            return null;
        }
        Object reason = map.get("reason");
        if (reason != null && !(reason instanceof String)) {
            problems.add(name + ": reason must be text");
            reason = null;
        }
        try {
            return new Rule(action, reason == null || ((String) reason).isBlank() ? "rule #" + number
                    : ((String) reason).strip(), Rule.conditionOf(map.get("match")));
        } catch (IllegalArgumentException e) {
            problems.add(name + " is skipped: " + e.getMessage());
            return null;
        }
    }

    /** Reading one top-level value, falling back to the defaults' on a missing or wrong one. */
    private record Read(Map<String, Object> config, Map<String, Object> defaults, List<String> problems) {
        Object value(String key) {
            return config.containsKey(key) ? config.get(key) : defaults.get(key);
        }

        String string(String key) {
            Object value = value(key);
            if (value == null || value instanceof String) {
                return (String) value;
            }
            // An API key that YAML read as a number still means that key.
            if (value instanceof Number) {
                return String.valueOf(value);
            }
            problems.add(key + " must be text in quotes");
            return (String) defaults.get(key);
        }

        boolean bool(String key) {
            Object value = value(key);
            if (value instanceof Boolean b) {
                return b;
            }
            problems.add(key + " is '" + value + "', not true or false");
            return Boolean.TRUE.equals(defaults.get(key));
        }

        int integer(String key, int min, int max) {
            Object value = value(key);
            if (value instanceof Integer i && i >= min && i <= max) {
                return i;
            }
            problems.add(key + " is '" + value + "', not a whole number from " + min + " to " + max);
            return (Integer) defaults.get(key);
        }

        Map<String, Object> section(String key) {
            Object value = config.get(key);
            if (value == null) {
                return map(defaults.get(key));
            }
            if (!(value instanceof Map)) {
                problems.add(key + " must be a section of its own");
                return map(defaults.get(key));
            }
            return map(value);
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> map(Object value) {
            if (!(value instanceof Map<?, ?> map)) {
                return Map.of();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<Object, Object>) map).forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
    }
}
