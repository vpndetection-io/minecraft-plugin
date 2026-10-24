package io.vpndetection.minecraft;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.regex.Pattern;

/** An address range, like 203.0.113.0/24, or one address as the range holding only it. */
record Range(byte[] network, int bits) {
    private static final Pattern IPV4 = Pattern.compile(
            "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");

    /**
     * An address written as a literal, or null for anything else.
     *
     * <p>{@link InetAddress#getByName} looks a host name up in DNS, so nothing but a literal ever
     * reaches it: an entry in bypass.txt or an argument to a command must never cost a lookup, or
     * stall on one. An IPv4-mapped IPv6 address comes back as the IPv4 address it carries.
     */
    static InetAddress literal(String text) {
        if (text == null || !(IPV4.matcher(text).matches() || IPV6.matcher(text).matches())) {
            return null;
        }
        try {
            return InetAddress.getByName(text);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** A range or a single address, or null when the text is neither. */
    static Range parse(String text) {
        int slash = text.indexOf('/');
        InetAddress address = literal(slash < 0 ? text : text.substring(0, slash));
        if (address == null) {
            return null;
        }
        int max = address.getAddress().length * 8;
        int bits = max;
        if (slash >= 0) {
            try {
                bits = Integer.parseInt(text.substring(slash + 1));
            } catch (NumberFormatException e) {
                return null;
            }
            if (bits < 0 || bits > max) {
                return null;
            }
        }
        return new Range(mask(address.getAddress(), bits), bits);
    }

    boolean contains(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == network.length && Arrays.equals(mask(bytes, bits), network);
    }

    /** The range as bypass.txt spells it: its network address, and the prefix unless it is one address. */
    String text() {
        try {
            String host = InetAddress.getByAddress(network).getHostAddress();
            return bits == network.length * 8 ? host : host + "/" + bits;
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] mask(byte[] address, int bits) {
        byte[] out = address.clone();
        for (int i = 0; i < out.length; i++) {
            int keep = Math.max(0, Math.min(8, bits - i * 8));
            out[i] &= (byte) (0xff << (8 - keep));
        }
        return out;
    }
}
