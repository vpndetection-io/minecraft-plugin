package io.vpndetection.minecraft;

/** Lookup answers in the shape each plan is served, as the API documents them. */
final class Answers {
    private Answers() {}

    static final String LOCATION_AT = "{\"continent\":\"EU\",\"continent_name\":\"Europe\",\"country\":\"AT\","
            + "\"country_name\":\"Austria\",\"timezone\":\"Europe/Vienna\",\"currency_code\":\"EUR\","
            + "\"currency_name\":\"Euro\",\"currency_symbol\":\"E\",\"flag_emoji\":\"\",\"flag_unicode\":\"\"}";
    static final String LOCATION_BR = "{\"continent\":\"SA\",\"continent_name\":\"South America\",\"country\":\"BR\","
            + "\"country_name\":\"Brazil\",\"timezone\":\"America/Sao_Paulo\",\"currency_code\":\"BRL\","
            + "\"currency_name\":\"Real\",\"currency_symbol\":\"R$\",\"flag_emoji\":\"\",\"flag_unicode\":\"\"}";
    static final String LOCATION_UNKNOWN = "{\"continent\":\"\",\"continent_name\":\"\",\"country\":\"\","
            + "\"country_name\":\"\",\"timezone\":\"\",\"currency_code\":\"\",\"currency_name\":\"\","
            + "\"currency_symbol\":\"\",\"flag_emoji\":\"\",\"flag_unicode\":\"\"}";
    static final String ASN_M247 = "{\"asn\":9009,\"name\":\"M247 Europe SRL\",\"domain\":\"m247global.com\","
            + "\"type\":\"hosting\",\"prefix\":\"146.70.116.0/24\"}";
    static final String ASN_ISP = "{\"asn\":28573,\"name\":\"Claro NXT\",\"domain\":\"claro.com.br\","
            + "\"type\":\"isp\",\"prefix\":\"177.0.0.0/14\"}";

    /** No key: the address and whether it is a VPN, nothing else. */
    static String keyless(String ip, boolean vpn) {
        return "{\"ip\":\"" + ip + "\",\"is_vpn\":" + vpn + "}";
    }

    /** A free key: Tor and relays beside the VPN flag, and where the address is. */
    static String free(String ip, boolean vpn, boolean tor, String location, String asn) {
        return "{\"ip\":\"" + ip + "\",\"is_vpn\":" + vpn + ",\"is_relay\":false,\"is_tor\":" + tor
                + ",\"relay\":{},\"tor\":" + (tor ? "{\"provider\":\"\",\"confidence\":\"high\",\"last_seen\":\"2026-10-20\"}" : "{}")
                + ",\"location\":" + location + ",\"asn\":" + asn + "}";
    }

    /** A paid plan: every member, here a NordVPN exit in an OVH range also seen in a residential pool. */
    static String paid(String ip) {
        return "{\"ip\":\"" + ip + "\",\"is_vpn\":true,\"is_hosting\":true,\"is_relay\":false,\"is_tor\":false,"
                + "\"is_cdn\":false,\"is_resproxy\":true,\"is_dcproxy\":false,\"is_mobproxy\":false,\"is_pubproxy\":false,"
                + "\"vpn\":{\"provider\":\"nordvpn\",\"confidence\":\"high\",\"last_seen\":\"2026-10-20\"},"
                + "\"hosting\":{\"provider\":\"ovh\",\"confidence\":\"high\",\"last_seen\":\"2026-10-20\"},"
                + "\"relay\":{},\"tor\":{},\"cdn\":{},"
                + "\"resproxy\":{\"provider\":\"brightdata\",\"first_seen\":\"2026-08-01\",\"last_seen\":\"2026-10-20\","
                + "\"hits\":7,\"hits_days_pct\":42,\"providers_num\":2},"
                + "\"dcproxy\":{},\"mobproxy\":{},\"pubproxy\":{},"
                + "\"location\":" + LOCATION_AT + ",\"asn\":" + ASN_M247 + "}";
    }

    /** A paid plan, an ordinary home connection: every flag false, every detail empty. */
    static String paidClean(String ip, String location, String asn) {
        return "{\"ip\":\"" + ip + "\",\"is_vpn\":false,\"is_hosting\":false,\"is_relay\":false,\"is_tor\":false,"
                + "\"is_cdn\":false,\"is_resproxy\":false,\"is_dcproxy\":false,\"is_mobproxy\":false,\"is_pubproxy\":false,"
                + "\"vpn\":{},\"hosting\":{},\"relay\":{},\"tor\":{},\"cdn\":{},\"resproxy\":{},\"dcproxy\":{},"
                + "\"mobproxy\":{},\"pubproxy\":{},\"location\":" + location + ",\"asn\":" + asn + "}";
    }
}
