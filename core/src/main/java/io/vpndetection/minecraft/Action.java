package io.vpndetection.minecraft;

import java.util.Locale;

/** What a check, a country list or a rule does to a login it matches, weakest first. */
public enum Action {
    ALLOW,
    ALERT,
    BLOCK;

    /** The spelling config.yml uses. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The action a config value names, or null for anything else. */
    static Action parse(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        for (Action action : values()) {
            if (action.key().equals(text.trim().toLowerCase(Locale.ROOT))) {
                return action;
            }
        }
        return null;
    }
}
