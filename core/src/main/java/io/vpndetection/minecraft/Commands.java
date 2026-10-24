package io.vpndetection.minecraft;

import io.vpndetection.Result;
import io.vpndetection.VPNDetectionException;
import io.vpndetection.model.Asn;
import io.vpndetection.model.ClassDetail;
import io.vpndetection.model.Entitlement;
import io.vpndetection.model.Location;
import io.vpndetection.model.ProxyDetail;
import io.vpndetection.model.VpnDetail;

import java.io.IOException;
import java.net.InetAddress;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * /vpndetection, or /vpnd: the same subcommands on every platform. Anything that waits on the network
 * runs on the engine's threads and answers through {@link Sender}, which any thread may use.
 */
public final class Commands {
    private static final List<String> SUBCOMMANDS =
            List.of("check", "login", "status", "bypass", "reload", "help");
    private static final int LIST_LIMIT = 50;

    private final Engine engine;

    Commands(Engine engine) {
        this.engine = engine;
    }

    public void execute(Sender sender, String[] args) {
        if (!sender.hasPermission(Engine.ADMIN)) {
            say(sender, "&cYou need the " + Engine.ADMIN + " permission for that.");
            return;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "check" -> check(sender, args);
            case "login" -> engine.signIn().start(sender, engine::saveApiKey);
            case "status" -> status(sender);
            case "bypass" -> bypass(sender, args);
            case "reload" -> reload(sender);
            default -> help(sender);
        }
    }

    public List<String> complete(Sender sender, String[] args) {
        if (!sender.hasPermission(Engine.ADMIN) || args.length == 0) {
            return List.of();
        }
        if (args.length == 1) {
            return matching(SUBCOMMANDS.stream(), args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("check") && args.length == 2) {
            return matching(engine.platform().playerNames().stream(), args[1]);
        }
        if (sub.equals("bypass")) {
            if (args.length == 2) {
                return matching(Stream.of("add", "remove", "list"), args[1]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("add")) {
                return matching(engine.platform().playerNames().stream(), args[2]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("remove")) {
                return matching(engine.state().bypass().entries().stream().map(Bypass.Entry::text), args[2]);
            }
        }
        return List.of();
    }

    private void help(Sender sender) {
        say(sender, "&3VPNDetection " + engine.version() + " &7commands:");
        say(sender, "&f/vpnd check <player|address> &7look one up, and say what a login would get");
        say(sender, "&f/vpnd login &7sign in, and save an API key in config.yml");
        say(sender, "&f/vpnd status &7your plan, and what's been checked since the server started");
        say(sender, "&f/vpnd bypass add|remove|list [player|address|range] &7never check these");
        say(sender, "&f/vpnd reload &7read config.yml and bypass.txt again");
    }

    private void check(Sender sender, String[] args) {
        if (args.length != 2) {
            say(sender, "&cUsage: /vpnd check <player|address>");
            return;
        }
        String target = args[1];
        Optional<Platform.OnlinePlayer> player = engine.platform().player(target);
        InetAddress address = player.map(Platform.OnlinePlayer::address).orElseGet(() -> Range.literal(target));
        if (address == null) {
            say(sender, "&cNo online player or address is called " + target + ".");
            return;
        }
        String name = player.map(Platform.OnlinePlayer::name).orElse(null);
        UUID uuid = player.map(Platform.OnlinePlayer::uuid).orElse(null);
        engine.executor().execute(() -> {
            Engine.State state = engine.state();
            Result result;
            try {
                result = engine.lookup(address);
            } catch (VPNDetectionException e) {
                say(sender, "&cThe lookup failed: " + e.getMessage());
                return;
            }
            say(sender, "&3VPNDetection &7on &f" + address.getHostAddress()
                    + (name == null ? "" : " &7(" + name + ")"));
            if (result.isBogon()) {
                say(sender, "&7A private address: nothing is looked up for it, and it's always let in.");
                return;
            }
            say(sender, flags(result));
            say(sender, where(result));
            boolean exempt = state.bypass().covers(name, uuid, address);
            say(sender, "&7A login gets: " + outcome(state.guard().judge(result), exempt));
        });
    }

    private void status(Sender sender) {
        Engine.State state = engine.state();
        say(sender, "&3VPNDetection " + engine.version() + " &7on " + engine.platform().server());
        say(sender, "&7Checks: &f" + Engine.describe(state.settings()));
        say(sender, "&7Since the server started: &f" + engine.checked.get() + " &7logins checked, &f"
                + engine.refused.get() + " &7refused, &f" + engine.flagged.get() + " &7flagged, &f"
                + engine.bypassed.get() + " &7bypassed, &f" + engine.failed.get() + " &7lookups failed.");
        if (state.apiKey() == null) {
            say(sender, "&7No API key, so only VPNs are checked. Run &f/vpnd login &7to sign in.");
            return;
        }
        engine.executor().execute(() -> {
            try {
                say(sender, "&7Key &f" + Login.mask(state.apiKey()) + " &7from " + state.keySource()
                        + ": &f" + usage(engine.entitlement()));
            } catch (VPNDetectionException e) {
                say(sender, "&cCouldn't read the key's plan: " + e.getMessage());
            }
        });
    }

    private void bypass(Sender sender, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        Bypass bypass = engine.state().bypass();
        if (action.equals("list")) {
            List<Bypass.Entry> entries = bypass.entries();
            if (entries.isEmpty()) {
                say(sender, "&7bypass.txt is empty.");
                return;
            }
            say(sender, "&7Never checked (" + entries.size() + "):");
            entries.stream().limit(LIST_LIMIT).forEach(e -> say(sender, "&f" + e.text()));
            if (entries.size() > LIST_LIMIT) {
                say(sender, "&7... and " + (entries.size() - LIST_LIMIT) + " more, in bypass.txt.");
            }
            return;
        }
        if (args.length != 3 || !(action.equals("add") || action.equals("remove"))) {
            say(sender, "&cUsage: /vpnd bypass add|remove <player|uuid|address|range>, or /vpnd bypass list");
            return;
        }
        // An online player is added by UUID, which survives a change of name, with the name beside it.
        Optional<Platform.OnlinePlayer> player = engine.platform().player(args[2]);
        List<Bypass.Entry> entries = new ArrayList<>();
        player.ifPresent(p -> entries.add(Bypass.parse(p.uuid().toString())));
        if (player.isEmpty() || action.equals("remove")) {
            Bypass.Entry entry = Bypass.parse(args[2]);
            if (entry == null) {
                say(sender, "&c" + args[2] + " is not a player name, UUID, address or range.");
                return;
            }
            entries.add(entry);
        }
        try {
            boolean changed = false;
            for (Bypass.Entry entry : entries) {
                if (action.equals("add")) {
                    String comment = player.map(p -> p.name() + ", ").orElse("")
                            + "added " + LocalDate.now(ZoneOffset.UTC);
                    changed |= bypass.add(entry, comment);
                } else {
                    changed |= bypass.remove(entry);
                }
            }
            String what = player.map(Platform.OnlinePlayer::name).orElse(entries.get(0).text());
            if (action.equals("add")) {
                say(sender, changed ? "&a" + what + " is never checked now."
                        : "&7" + what + " was in bypass.txt already.");
            } else {
                say(sender, changed ? "&a" + what + " is checked again."
                        : "&7" + what + " wasn't in bypass.txt.");
            }
        } catch (IOException e) {
            say(sender, "&cCouldn't write bypass.txt: " + e.getMessage());
        }
    }

    private void reload(Sender sender) {
        List<String> problems = engine.reload();
        say(sender, "&aRead config.yml and bypass.txt again. &7Checks: &f"
                + Engine.describe(engine.state().settings()));
        for (String problem : problems) {
            say(sender, "&e" + problem);
        }
    }

    /** A plan and what is left of it, like "Free plan, 1,234 of 50,000 lookups used, renewing 2026-11-01". */
    static String usage(Entitlement entitlement) {
        String plan = entitlement.getPlan() == null || entitlement.getPlan().getKey() == null ? "your"
                : capitalize(entitlement.getPlan().getKey());
        StringBuilder out = new StringBuilder(plan + " plan");
        if (entitlement.getUsage() != null) {
            Long requests = entitlement.getUsage().getRequests();
            Long quota = entitlement.getUsage().getQuota();
            if (requests != null && quota != null && quota > 0) {
                out.append(String.format(Locale.ROOT, ", %,d of %,d lookups used", requests, quota));
            } else if (requests != null) {
                out.append(String.format(Locale.ROOT, ", %,d lookups used", requests));
            }
            if (entitlement.getUsage().getWindowEnd() != null) {
                out.append(", renewing ").append(entitlement.getUsage().getWindowEnd().toLocalDate());
            }
        }
        return out.toString();
    }

    private static String flags(Result r) {
        List<String> parts = new ArrayList<>();
        parts.add(field("VPN", Optional.of(r.isVpn()), r.vpn().map(VpnDetail::getProvider)));
        parts.add(field("Tor", r.isTor(), Optional.empty()));
        parts.add(field("hosting", r.isHosting(), r.hosting().map(ClassDetail::getProvider)));
        parts.add("&7proxy " + proxies(r));
        parts.add(field("relay", r.isRelay(), r.relay().map(ClassDetail::getProvider)));
        return String.join("&7, ", parts);
    }

    private static String field(String label, Optional<Boolean> flag, Optional<String> provider) {
        if (flag.isEmpty()) {
            return "&7" + label + " &8not in your plan";
        }
        if (!flag.get()) {
            return "&7" + label + " &fno";
        }
        return "&7" + label + " &cyes" + provider.filter(p -> !p.isBlank()).map(p -> " (" + p + ")").orElse("");
    }

    private static String proxies(Result r) {
        if (!Check.PROXY.served(r)) {
            return "&8not in your plan";
        }
        List<String> kinds = new ArrayList<>();
        proxy(kinds, "residential", r.isResproxy(), r.resproxy().map(ProxyDetail::getProvider));
        proxy(kinds, "datacenter", r.isDcproxy(), r.dcproxy().map(ProxyDetail::getProvider));
        proxy(kinds, "mobile", r.isMobproxy(), r.mobproxy().map(ProxyDetail::getProvider));
        proxy(kinds, "public", r.isPubproxy(), Optional.empty());
        return kinds.isEmpty() ? "&fno" : "&c" + String.join(", ", kinds);
    }

    private static void proxy(List<String> kinds, String kind, Optional<Boolean> flag,
            Optional<String> provider) {
        if (flag.orElse(false)) {
            kinds.add(kind + provider.filter(p -> !p.isBlank()).map(p -> " (" + p + ")").orElse(""));
        }
    }

    private static String where(Result r) {
        String place = r.location()
                .filter(l -> l.getCountry() != null && !l.getCountry().isEmpty())
                .map(Commands::country)
                .orElse(r.location().isPresent() ? "unknown" : "&8not in your plan");
        String network = r.asn()
                .map(Commands::network)
                .orElse("&8not in your plan");
        return "&7Country &f" + place + "&7, network &f" + network;
    }

    private static String country(Location l) {
        return (l.getCountryName() == null || l.getCountryName().isEmpty() ? "" : l.getCountryName() + " ")
                + "(" + l.getCountry() + ")";
    }

    private static String network(Asn asn) {
        if (asn.getAsn() == null || asn.getAsn() == 0) {
            return "none announces it";
        }
        String type = asn.getType() == null || asn.getType().isEmpty() ? "" : " (" + asn.getType() + ")";
        return "AS" + asn.getAsn() + " " + (asn.getName() == null ? "" : asn.getName()) + type;
    }

    private static String outcome(Verdict verdict, boolean exempt) {
        if (exempt) {
            return "&alet in, as bypass.txt lists it";
        }
        return switch (verdict.action()) {
            case BLOCK -> "&crefused&7, for " + verdict.reason();
            case ALERT -> "&elet in, and staff alerted&7, for " + verdict.reason();
            case ALLOW -> "&alet in";
        };
    }

    private static String capitalize(String word) {
        return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    private static void say(Sender sender, String text) {
        sender.send(Text.colorize(text));
    }

    private static List<String> matching(Stream<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }
}
