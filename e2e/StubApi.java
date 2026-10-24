import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * The lookup API for the end-to-end run, on the loopback: a fixed answer per address, and one line
 * per request appended to a log, its path and User-Agent, for run.sh to check.
 *
 *   java StubApi.java <port> <log file>
 */
public final class StubApi {
    private static final String LOCATION = "{\"continent\":\"%s\",\"continent_name\":\"\",\"country\":\"%s\","
            + "\"country_name\":\"%s\",\"timezone\":\"\",\"currency_code\":\"\",\"currency_name\":\"\","
            + "\"currency_symbol\":\"\",\"flag_emoji\":\"\",\"flag_unicode\":\"\"}";
    private static final String ASN = "{\"asn\":%d,\"name\":\"%s\",\"domain\":\"\",\"type\":\"%s\",\"prefix\":\"%s\"}";
    private static final String EMPTY = "\"is_cdn\":false,\"is_dcproxy\":false,\"is_mobproxy\":false,\"is_pubproxy\":false,"
            + "\"is_relay\":false,\"is_tor\":false,\"cdn\":{},\"dcproxy\":{},\"mobproxy\":{},\"pubproxy\":{},\"relay\":{},\"tor\":{}";

    private static final Map<String, String> ANSWERS = Map.of(
            // A NordVPN exit in an OVH range, also seen in a residential proxy pool.
            "/45.83.91.1", "{\"ip\":\"45.83.91.1\",\"is_vpn\":true,\"is_hosting\":true,\"is_resproxy\":true," + EMPTY
                    + ",\"vpn\":{\"provider\":\"nordvpn\",\"confidence\":\"high\",\"last_seen\":\"2026-10-20\"},"
                    + "\"hosting\":{\"provider\":\"ovh\",\"confidence\":\"high\",\"last_seen\":\"2026-10-20\"},"
                    + "\"resproxy\":{\"provider\":\"brightdata\",\"first_seen\":\"2026-08-01\",\"last_seen\":\"2026-10-20\","
                    + "\"hits\":7,\"hits_days_pct\":42,\"providers_num\":2},"
                    + "\"location\":" + String.format(LOCATION, "EU", "FR", "France") + ","
                    + "\"asn\":" + String.format(ASN, 16276, "OVH SAS", "hosting", "45.83.91.0/24") + "}",
            // A home connection in Brazil.
            "/177.1.2.3", "{\"ip\":\"177.1.2.3\",\"is_vpn\":false,\"is_hosting\":false,\"is_resproxy\":false," + EMPTY
                    + ",\"vpn\":{},\"hosting\":{},\"resproxy\":{},"
                    + "\"location\":" + String.format(LOCATION, "SA", "BR", "Brazil") + ","
                    + "\"asn\":" + String.format(ASN, 28573, "Claro NXT", "isp", "177.0.0.0/14") + "}",
            // A home connection in a country the run's config.yml refuses.
            "/36.110.1.2", "{\"ip\":\"36.110.1.2\",\"is_vpn\":false,\"is_hosting\":false,\"is_resproxy\":false," + EMPTY
                    + ",\"vpn\":{},\"hosting\":{},\"resproxy\":{},"
                    + "\"location\":" + String.format(LOCATION, "AS", "CN", "China") + ","
                    + "\"asn\":" + String.format(ASN, 4847, "China Networks Inter-Exchange", "isp", "36.110.0.0/16") + "}",
            // A VPN that bypass.txt lists, so it must never be asked about.
            "/8.8.4.4", "{\"ip\":\"8.8.4.4\",\"is_vpn\":true}",
            "/api/v1/entitlement", "{\"org_id\":\"f32191d0-ef02-450e-a505-eb5814c35cab\","
                    + "\"apikey\":{\"id\":\"10c2b437-3aa2-4a63-bd17-8e7c8c7f0def\",\"expires\":null,\"allowed_cidrs\":[]},"
                    + "\"plan\":{\"key\":\"max\",\"tier\":\"max\"},\"usage\":{\"requests\":12,\"quota\":5000000,"
                    + "\"hard_limit\":null,\"window_start\":\"2026-10-01T00:00:00Z\",\"window_end\":\"2026-11-01T00:00:00Z\"}}");

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(args[0]);
        Path log = Path.of(args[1]);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String agent = exchange.getRequestHeaders().getFirst("User-Agent");
            synchronized (StubApi.class) {
                Files.writeString(log, path + "\t" + agent + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            String answer = ANSWERS.get(path);
            byte[] body = (answer != null ? answer : "{\"error\":\"not a valid IP address\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(answer != null ? 200 : 400, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        System.out.println("listening on " + port);
    }
}
