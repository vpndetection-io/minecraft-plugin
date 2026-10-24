package io.vpndetection.minecraft;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {
    private final List<String> problems = new ArrayList<>();

    private Settings parse(String yaml) {
        return Settings.parse(ConfigFile.parse(yaml), ConfigFile.defaults(), problems);
    }

    @Test
    void theShippedConfigReadsWithoutAProblem() {
        Settings settings = parse("");
        assertEquals(List.of(), problems);
        assertNull(settings.apiKey);
        assertEquals(Action.BLOCK, settings.checks.get(Check.VPN));
        assertEquals(Action.BLOCK, settings.checks.get(Check.TOR));
        assertEquals(Action.BLOCK, settings.checks.get(Check.HOSTING));
        assertEquals(Action.BLOCK, settings.checks.get(Check.PROXY));
        assertEquals(Action.ALLOW, settings.checks.get(Check.RELAY));
        assertEquals(Duration.ofMinutes(60), settings.cacheTtl);
        assertTrue(settings.message("kick").contains("{reason}"));
        assertEquals(List.of(), settings.rules);
    }

    @Test
    void aMistakeIsReportedAndTheDefaultTakesItsPlace() {
        Settings settings = parse("""
                api-key: 12345
                chekcs: {}
                checks: {vpn: blok, tor: ALERT, socks: block}
                fail-closed: maybe
                cache-minutes: -1
                messages: {kick: 7, motd: hi}
                """);
        assertEquals("12345", settings.apiKey);
        assertEquals(Action.BLOCK, settings.checks.get(Check.VPN));
        assertEquals(Action.ALERT, settings.checks.get(Check.TOR));
        assertEquals(Action.BLOCK, settings.checks.get(Check.HOSTING));
        assertEquals(false, settings.failClosed);
        assertEquals(Duration.ofMinutes(60), settings.cacheTtl);
        assertTrue(settings.message("kick").contains("{reason}"));
        assertEquals(List.of(
                "'chekcs' is not a setting",
                "checks.socks is not a check; they are vpn, tor, hosting, proxy and relay",
                "checks.vpn is 'blok', not block, alert or allow",
                "messages.motd is not a message",
                "messages.kick must be text in quotes",
                "fail-closed is 'maybe', not true or false",
                "cache-minutes is '-1', not a whole number from 0 to 10080"), problems);
    }

    @Test
    void countriesAreCodesInCapitalsAndNorwayIsNotFalse() {
        Settings settings = parse("countries: {block: [cn, RU, NO, 'no', Narnia], allow: [us]}");
        assertEquals(Set.of("CN", "RU", "NO"), settings.blockCountries);
        assertEquals(Set.of("US"), settings.allowCountries);
        assertEquals(List.of("countries.block: 'Narnia' is not a two-letter country code"), problems);
    }

    @Test
    void aRuleThatCouldBlockEveryoneIsSkipped() {
        Settings settings = parse("""
                rules:
                  - {action: block, match: {is_vpn: false}}
                  - {action: allow, match: {is_vpn: true}}
                  - {action: block}
                  - {action: alert, reason: fast, match: {resproxy: {hits: {gte: 5, lt: 100}}}, why: no}
                  - nonsense
                """);
        assertEquals(1, settings.rules.size());
        assertEquals("fast", settings.rules.get(0).reason());
        assertEquals(Action.ALERT, settings.rules.get(0).action());
        assertEquals(5, problems.size(), problems.toString());
        assertTrue(problems.get(0).startsWith("rules #1 is skipped:") && problems.get(0).contains("constrains nothing"),
                problems.get(0));
        assertEquals("rules #2: action must be block or alert, so it is skipped", problems.get(1));
        assertEquals("rules #3 is skipped: match must name at least one field of the answer", problems.get(2));
        assertEquals("rules #4: 'why' is not part of a rule; it has action, reason and match", problems.get(3));
        assertEquals("rules #5 must have an action and a match", problems.get(4));
    }

    @Test
    void aRuleOnNorwayIsNotARuleOnFalse() {
        Settings settings = parse("rules: [{action: block, match: {location: {country: NO}}}]");
        assertEquals(List.of(), problems);
        assertEquals("NO", ((java.util.Map<?, ?>) settings.rules.get(0).condition().get(0).get("location")).get("country"));
    }

    @Test
    void aBoundThatIsNotANumberIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Rule.conditionOf(ConfigFile.parse("resproxy: {hits: {gte: many}}")));
        assertEquals("resproxy.hits.gte must be a number", e.getMessage());
    }

    @Test
    void aFileThatIsNotYamlNamesTheLine() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConfigFile.parse("checks:\n  vpn: block\n tor: [\n"));
        assertTrue(e.getMessage().startsWith("line "), e.getMessage());
    }

    @Test
    void zeroMinutesTurnsTheCacheOff() {
        assertEquals(Duration.ZERO, parse("cache-minutes: 0").cacheTtl);
    }
}
