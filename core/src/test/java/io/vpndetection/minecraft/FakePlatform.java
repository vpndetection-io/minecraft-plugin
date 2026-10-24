package io.vpndetection.minecraft;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** A server with no players but those a test puts online, which keeps everything it is told. */
final class FakePlatform implements Platform {
    private final Path dir;
    final List<String> infos = new CopyOnWriteArrayList<>();
    final List<String> warnings = new CopyOnWriteArrayList<>();
    final List<String> notified = new CopyOnWriteArrayList<>();
    final Map<String, OnlinePlayer> online = new ConcurrentHashMap<>();

    FakePlatform(Path dir) {
        this.dir = dir;
    }

    @Override
    public String server() {
        return "Fake 1.0";
    }

    @Override
    public Path dataDirectory() {
        return dir;
    }

    @Override
    public void info(String message) {
        infos.add(message);
    }

    @Override
    public void warn(String message) {
        warnings.add(message);
    }

    @Override
    public void notify(String permission, String text) {
        notified.add(permission + " " + text);
    }

    @Override
    public Optional<OnlinePlayer> player(String name) {
        return online.values().stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst();
    }

    @Override
    public List<String> playerNames() {
        return online.values().stream().map(OnlinePlayer::name).toList();
    }
}
