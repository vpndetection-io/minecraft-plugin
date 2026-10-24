package io.vpndetection.minecraft;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Messages as server owners write them: {@code &} color codes, {@code &#rrggbb} for any color, and
 * {@code {name}} placeholders.
 *
 * <p>Every platform takes the section-sign form this produces: Bukkit and BungeeCord as they are, and
 * Paper and Velocity through Adventure's legacy serializer. {@code &#rrggbb} becomes the
 * {@code §x§r§r§g§g§b§b} form that 1.16 and later clients draw.
 */
public final class Text {
    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    private static final Pattern CODE = Pattern.compile("&([0-9a-fk-orA-FK-OR])");
    private static final Pattern SECTION = Pattern.compile("§[0-9a-fk-orxA-FK-ORX]");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z]+)}");

    private Text() {}

    /**
     * Turns the template's color codes into section signs, then fills its {@code {name}} placeholders.
     * In that order, so a value is never read as color codes: an {@code &} in a rule's reason stays an
     * ampersand.
     */
    public static String format(String template, Map<String, String> values) {
        return fill(colorize(template), values);
    }

    /** {@code &} codes and {@code &#rrggbb} as section signs. */
    public static String colorize(String text) {
        Matcher hex = HEX.matcher(text);
        StringBuilder out = new StringBuilder();
        while (hex.find()) {
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.group(1).toCharArray()) {
                replacement.append('§').append(Character.toLowerCase(c));
            }
            hex.appendReplacement(out, Matcher.quoteReplacement(replacement.toString()));
        }
        hex.appendTail(out);
        return CODE.matcher(out).replaceAll(m -> "§" + m.group(1).toLowerCase(Locale.ROOT));
    }

    /** Text with every color code taken out, for a log line. */
    public static String plain(String text) {
        return SECTION.matcher(colorize(text)).replaceAll("");
    }

    // One pass, so a value that happens to contain {player} is not filled again.
    private static String fill(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
