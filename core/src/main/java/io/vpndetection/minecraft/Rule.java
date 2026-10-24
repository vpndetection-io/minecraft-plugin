package io.vpndetection.minecraft;

import io.vpndetection.Result;
import io.vpndetection.middleware.Bound;
import io.vpndetection.middleware.Condition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One entry of config.yml's {@code rules}: a condition in the SDK's language, and what to do when
 * it holds.
 *
 * <p>The matching is the SDK's {@link Condition}, the same one every framework middleware uses, so a
 * rule means here exactly what a block condition means there. What is built here is only the
 * translation from parsed YAML, where a bound arrives as a map of gte/gt/lte/lt.
 */
record Rule(Action action, String reason, List<Map<String, Object>> condition) {
    /** Whether the answer satisfies the rule. */
    boolean matches(Result result) {
        return Condition.matches(condition, result);
    }

    /** The members the rule names that this answer lacks: the plan does not serve them. */
    List<String> missing(Result result) {
        return Condition.missingMembers(condition, result);
    }

    /**
     * A rule's match from YAML.
     *
     * @throws IllegalArgumentException when it is not a map, or constrains nothing, which would match
     *     every answer.
     */
    static List<Map<String, Object>> conditionOf(Object match) {
        if (!(match instanceof Map<?, ?> map) || map.isEmpty()) {
            throw new IllegalArgumentException("match must name at least one field of the answer");
        }
        List<Map<String, Object>> condition = List.of(object(map, ""));
        Condition.validate(condition);
        return condition;
    }

    private static Map<String, Object> object(Map<?, ?> map, String path) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            out.put(key, value(entry.getValue(), path.isEmpty() ? key : path + "." + key));
        }
        return out;
    }

    private static Object value(Object raw, String path) {
        if (raw instanceof Map<?, ?> map) {
            return isBound(map) ? bound(map, path) : object(map, path);
        }
        if (raw instanceof List<?> list) {
            List<Object> anyOf = new ArrayList<>();
            for (Object entry : list) {
                anyOf.add(value(entry, path));
            }
            return anyOf;
        }
        // YAML 1.1 reads an unquoted NO as false. A country is the one place a member's value is
        // a two-letter code that can collide with it, and Norway's is the one that does.
        if (Boolean.FALSE.equals(raw) && path.equals("location.country")) {
            return "NO";
        }
        return raw;
    }

    private static boolean isBound(Map<?, ?> map) {
        return !map.isEmpty()
                && map.keySet().stream().allMatch(k -> Condition.BOUND_KEYS.contains(String.valueOf(k)));
    }

    private static Bound bound(Map<?, ?> map, String path) {
        Bound bound = null;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof Number number)) {
                throw new IllegalArgumentException(path + "." + entry.getKey() + " must be a number");
            }
            double value = number.doubleValue();
            bound = switch (String.valueOf(entry.getKey())) {
                case "gte" -> bound == null ? Bound.gte(value) : bound.andGte(value);
                case "gt" -> bound == null ? Bound.gt(value) : bound.andGt(value);
                case "lte" -> bound == null ? Bound.lte(value) : bound.andLte(value);
                default -> bound == null ? Bound.lt(value) : bound.andLt(value);
            };
        }
        return bound;
    }
}
