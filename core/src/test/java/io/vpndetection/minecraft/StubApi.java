package io.vpndetection.minecraft;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** The API on the loopback: an answer per path, and a record of every request it was sent. */
final class StubApi implements AutoCloseable {
    record Request(String method, String path, String body, Map<String, List<String>> headers) {
        String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }
    }

    record Answer(int status, String body, long delayMillis) {
        static Answer ok(String body) {
            return new Answer(200, body, 0);
        }
    }

    private final HttpServer server;
    private final Map<String, Function<Request, Answer>> routes = new ConcurrentHashMap<>();
    final List<Request> requests = new CopyOnWriteArrayList<>();

    StubApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    void answer(String path, String json) {
        routes.put(path, request -> Answer.ok(json));
    }

    void route(String path, Function<Request, Answer> answer) {
        routes.put(path, answer);
    }

    long count(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).count();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body,
                Map.copyOf(exchange.getRequestHeaders()));
        requests.add(request);
        Function<Request, Answer> route = routes.get(request.path());
        Answer answer = route == null ? new Answer(404, "{\"error\":\"not found\"}", 0) : route.apply(request);
        if (answer.delayMillis() > 0) {
            try {
                Thread.sleep(answer.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(answer.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
