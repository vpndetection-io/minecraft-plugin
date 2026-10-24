import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * Joins a server as an offline-mode player from any address, and says how the login ended.
 *
 *   java Probe.java <host> <port> <name> <source address>
 *
 * The connection opens with a PROXY protocol header naming the source address, so a server that
 * trusts the header (Paper's proxies.proxy-protocol, Velocity's haproxy-protocol, BungeeCord's
 * proxy_protocol) sees the player arriving from it. Prints one line: SUCCESS, DISCONNECT and the
 * reason's JSON, or ERROR and why.
 */
public final class Probe {
    private static final Pattern PROTOCOL = Pattern.compile("\"protocol\"\\s*:\\s*(-?\\d+)");

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String name = args[2];
        String source = args[3];
        try {
            int protocol = protocol(host, port);
            System.out.println(login(host, port, name, source, protocol));
        } catch (IOException e) {
            System.out.println("ERROR " + e);
        }
    }

    // A status ping, whose answer names the protocol to log in with: a server's own, or for a proxy
    // the one asked with, when it speaks it.
    private static int protocol(String host, int port) throws IOException {
        try (Socket socket = connect(host, port, "10.255.0.1")) {
            OutputStream out = socket.getOutputStream();
            out.write(packet(0x00, varInt(767), string(host), u16(port), varInt(1)));
            out.write(packet(0x00));
            DataInputStream in = new DataInputStream(socket.getInputStream());
            byte[] body = readPacket(in, -1);
            String json = readString(new DataInputStream(new java.io.ByteArrayInputStream(body, 1, body.length - 1)));
            Matcher m = PROTOCOL.matcher(json);
            if (!m.find()) {
                throw new IOException("no protocol in the status answer: " + json);
            }
            return Integer.parseInt(m.group(1));
        }
    }

    private static String login(String host, int port, String name, String source, int protocol) throws IOException {
        try (Socket socket = connect(host, port, source)) {
            OutputStream out = socket.getOutputStream();
            out.write(packet(0x00, varInt(protocol), string(host), u16(port), varInt(2)));
            UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            if (protocol >= 764) {
                out.write(packet(0x00, string(name), uuid(uuid)));
            } else if (protocol >= 761) {
                out.write(packet(0x00, string(name), new byte[] {1}, uuid(uuid)));
            } else if (protocol >= 759) {
                out.write(packet(0x00, string(name), new byte[] {0}, new byte[] {0}));
            } else {
                out.write(packet(0x00, string(name)));
            }
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int threshold = -1;
            while (true) {
                byte[] body = readPacket(in, threshold);
                DataInputStream packet = new DataInputStream(new java.io.ByteArrayInputStream(body));
                int id = readVarInt(packet);
                switch (id) {
                    case 0x00 -> {
                        return "DISCONNECT " + readString(packet);
                    }
                    case 0x02 -> {
                        return "SUCCESS";
                    }
                    case 0x03 -> threshold = readVarInt(packet);
                    case 0x01 -> {
                        return "ERROR the server asked for encryption, so it is in online mode";
                    }
                    default -> {
                        // A login plugin message or anything else before the outcome: keep reading.
                    }
                }
            }
        }
    }

    private static Socket connect(String host, int port, String source) throws IOException {
        Socket socket = new Socket();
        socket.setSoTimeout(30_000);
        socket.connect(new InetSocketAddress(host, port), 10_000);
        boolean v6 = source.contains(":");
        String header = "PROXY " + (v6 ? "TCP6 " : "TCP4 ") + source + " " + (v6 ? "::1" : "127.0.0.1") + " 51234 "
                + port + "\r\n";
        socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
        return socket;
    }

    private static byte[] readPacket(DataInputStream in, int threshold) throws IOException {
        int length = readVarInt(in);
        byte[] frame = new byte[length];
        in.readFully(frame);
        if (threshold < 0) {
            return frame;
        }
        DataInputStream framed = new DataInputStream(new java.io.ByteArrayInputStream(frame));
        int dataLength = readVarInt(framed);
        byte[] rest = framed.readAllBytes();
        if (dataLength == 0) {
            return rest;
        }
        Inflater inflater = new Inflater();
        inflater.setInput(rest);
        byte[] out = new byte[dataLength];
        try {
            inflater.inflate(out);
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException(e);
        } finally {
            inflater.end();
        }
        return out;
    }

    private static byte[] packet(int id, byte[]... fields) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(varInt(id));
        for (byte[] field : fields) {
            body.write(field);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(varInt(body.size()));
        body.writeTo(out);
        return out.toByteArray();
    }

    private static byte[] varInt(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do {
            int b = value & 0x7f;
            value >>>= 7;
            out.write(value != 0 ? b | 0x80 : b);
        } while (value != 0);
        return out.toByteArray();
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("the connection closed");
            }
            value |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("a VarInt longer than five bytes");
    }

    private static byte[] string(String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(varInt(bytes.length));
        out.write(bytes);
        return out.toByteArray();
    }

    private static String readString(DataInputStream in) throws IOException {
        byte[] bytes = new byte[readVarInt(in)];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] u16(int value) {
        return new byte[] {(byte) (value >> 8), (byte) value};
    }

    private static byte[] uuid(UUID uuid) {
        byte[] out = new byte[16];
        long[] halves = {uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()};
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) (halves[i / 8] >> (8 * (7 - i % 8)));
        }
        return out;
    }
}
