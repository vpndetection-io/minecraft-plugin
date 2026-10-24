package io.vpndetection.minecraft;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextTest {
    @Test
    void ampersandCodesBecomeSectionSigns() {
        assertEquals("§cRed §lbold §rplain", Text.colorize("&cRed &Lbold &rplain"));
        assertEquals("AT&T stays", Text.colorize("AT&T stays"));
    }

    @Test
    void aHexColorBecomesTheFormClientsDraw() {
        assertEquals("§x§f§f§8§8§0§0orange", Text.colorize("&#FF8800orange"));
    }

    @Test
    void placeholdersAreFilledAfterColoringSoAValueIsNeverACode() {
        String out = Text.format("&7{player} from {ip}: &c{reason} {unknown}",
                Map.of("player", "Steve", "ip", "1.2.3.4", "reason", "&aa rule's {player}"));
        assertEquals("§7Steve from 1.2.3.4: §c&aa rule's {player} {unknown}", out);
    }

    @Test
    void aLogLineHasNoCodes() {
        assertEquals("Refused Steve: VPN", Text.plain("&8&lRefused &#00ff00Steve§r: VPN"));
    }
}
