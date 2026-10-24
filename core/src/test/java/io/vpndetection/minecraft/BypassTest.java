package io.vpndetection.minecraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BypassTest {
    @TempDir
    Path dir;

    @Test
    void rangesHoldTheirFirstAndLastAddressAndNothingPast() throws Exception {
        Range v4 = Range.parse("203.0.113.0/24");
        assertTrue(v4.contains(InetAddress.getByName("203.0.113.0")));
        assertTrue(v4.contains(InetAddress.getByName("203.0.113.255")));
        assertFalse(v4.contains(InetAddress.getByName("203.0.114.0")));
        assertFalse(v4.contains(InetAddress.getByName("203.0.112.255")));
        Range odd = Range.parse("198.51.100.77/27");
        assertEquals("198.51.100.64/27", odd.text());
        assertTrue(odd.contains(InetAddress.getByName("198.51.100.95")));
        assertFalse(odd.contains(InetAddress.getByName("198.51.100.96")));
        Range v6 = Range.parse("2001:db8::/33");
        assertTrue(v6.contains(InetAddress.getByName("2001:db8:7fff:ffff::1")));
        assertFalse(v6.contains(InetAddress.getByName("2001:db8:8000::")));
        assertFalse(v6.contains(InetAddress.getByName("203.0.113.1")));
        assertTrue(Range.parse("0.0.0.0/0").contains(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    void aMappedAddressIsTheIpv4AddressItCarries() throws Exception {
        assertTrue(Range.parse("203.0.113.0/24").contains(InetAddress.getByName("::ffff:203.0.113.9")));
        assertEquals("203.0.113.9", Range.parse("::ffff:203.0.113.9").text());
    }

    @Test
    void onlyALiteralIsAnAddressSoNothingIsLookedUpInDns() {
        assertNull(Range.literal("localhost"));
        assertNull(Range.literal("example.com"));
        assertNull(Range.literal("1.2.3"));
        assertNull(Range.literal("256.1.1.1"));
        assertNull(Range.literal("fe80::1%eth0"));
        assertNull(Range.parse("203.0.113.0/33"));
        assertNull(Range.parse("203.0.113.0/x"));
    }

    @Test
    void anEntryIsSpelledOneWayWhateverWayItWasWritten() {
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", Bypass.parse("069A79F444E94726A5BEFCA90E38AAF5").text());
        assertEquals("notch", Bypass.parse(" Notch ").text());
        assertEquals(".bedrock_player", Bypass.parse(".Bedrock_Player").text());
        assertEquals("2001:db8:0:0:0:0:0:1", Bypass.parse("2001:db8::1").text());
        assertNull(Bypass.parse("two words"));
        assertNull(Bypass.parse("a-name-with-dashes"));
    }

    @Test
    void editsKeepTheFilesOtherLinesAndSurviveAReread() throws Exception {
        Path file = dir.resolve("bypass.txt");
        Files.writeString(file, "# staff\nJeb_  # builder\nnot a thing\n10.1.0.0/16\n");
        List<String> problems = new ArrayList<>();
        Bypass bypass = Bypass.load(file, problems);
        assertEquals(List.of("bypass.txt line 3: 'not a thing' is not a player name, UUID, address or range"), problems);
        UUID uuid = UUID.randomUUID();

        assertTrue(bypass.add(Bypass.parse(uuid.toString()), "Dinnerbone, added 2026-10-24"));
        assertFalse(bypass.add(Bypass.parse(uuid.toString().toUpperCase()), null));
        assertTrue(bypass.covers("someone", uuid, null));
        assertTrue(bypass.remove(Bypass.parse("jeb_")));
        assertFalse(bypass.remove(Bypass.parse("jeb_")));

        assertEquals(List.of("# staff", "not a thing", "10.1.0.0/16", uuid + "  # Dinnerbone, added 2026-10-24"),
                Files.readAllLines(file));
        Bypass again = Bypass.load(file, new ArrayList<>());
        assertTrue(again.coversAddress(InetAddress.getByName("10.1.200.3")));
        assertTrue(again.covers(null, uuid, null));
        assertFalse(again.covers("Jeb_", null, null));
    }
}
