package io.vpndetection.minecraft;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * The JDK's client, sending a User-Agent that names this plugin and the server it runs on.
 *
 * <p>The SDK has no setting for one, and takes a client of its own in its place, so the header goes
 * on here, request by request. Everything else is the client the SDK would build: a 10 second connect
 * bound and no redirects followed.
 */
final class UserAgentHttpClient extends HttpClient {
    private final HttpClient delegate;
    private final String userAgent;

    UserAgentHttpClient(String userAgent) {
        this.delegate = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(Redirect.NEVER)
                .build();
        this.userAgent = userAgent;
    }

    private HttpRequest withUserAgent(HttpRequest request) {
        return HttpRequest.newBuilder(request, (name, value) -> !name.equalsIgnoreCase("User-Agent"))
                .header("User-Agent", userAgent)
                .build();
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        return delegate.send(withUserAgent(request), handler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
            HttpResponse.BodyHandler<T> handler) {
        return delegate.sendAsync(withUserAgent(request), handler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
            HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        return delegate.sendAsync(withUserAgent(request), handler, pushPromiseHandler);
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return delegate.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
        return delegate.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return delegate.proxy();
    }

    @Override
    public SSLContext sslContext() {
        return delegate.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return delegate.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return delegate.authenticator();
    }

    @Override
    public Version version() {
        return delegate.version();
    }

    @Override
    public Optional<Executor> executor() {
        return delegate.executor();
    }
}
