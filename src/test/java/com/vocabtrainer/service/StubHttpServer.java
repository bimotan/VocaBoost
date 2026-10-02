package com.vocabtrainer.service;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A local HTTP server on 127.0.0.1 that answers the online dictionaries' and the AI provider's
 * requests from a script, so their HTTP handling is tested without the network. Paths are matched exactly (decoded);
 * unscripted paths answer 404.
 */
final class StubHttpServer implements AutoCloseable {
    /** One scripted answer; {@code delay} makes the server wait before answering. */
    record Answer(int status, String body, Map<String, String> headers, Duration delay) {
        static Answer json(int status, String body) {
            return new Answer(status, body, Map.of("Content-Type", "application/json; charset=utf-8"), Duration.ZERO);
        }

        Answer withHeader(String name, String value) {
            Map<String, String> all = new ConcurrentHashMap<>(headers);
            all.put(name, value);
            return new Answer(status, body, all, delay);
        }

        Answer after(Duration wait) {
            return new Answer(status, body, headers, wait);
        }
    }

    /** A request the server received: the decoded and the raw path, the raw query, the headers and the body. */
    record Request(String path, String rawPath, String rawQuery, Headers headers, String body) {
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch firstRequest = new CountDownLatch(1);

    StubHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    /** A client that talks to this server directly, never through a proxy. */
    static HttpClient client() {
        return HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    }

    StubHttpServer answer(String path, Answer answer) {
        answers.put(path, answer);
        return this;
    }

    StubHttpServer answer(String path, int status, String body) {
        return answer(path, Answer.json(status, body));
    }

    /** Where the server listens, e.g. to use it as the proxy of a client: then every request ends up here. */
    InetSocketAddress address() {
        return server.getAddress();
    }

    URI uri(String path) {
        return URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + path);
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    List<String> paths() {
        return requests.stream().map(Request::path).toList();
    }

    /** Waits until the first request arrived. */
    void awaitRequest() throws InterruptedException {
        if (!firstRequest.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new AssertionError("No request reached the stub server");
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            URI uri = exchange.getRequestURI();
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(uri.getPath(), uri.getRawPath(), uri.getRawQuery(), exchange.getRequestHeaders(),
                requestBody));
            firstRequest.countDown();
            Answer answer = answers.getOrDefault(uri.getPath(),
                Answer.json(404, "{\"title\":\"Not found\"}"));
            if (!answer.delay().isZero()) {
                try {
                    Thread.sleep(answer.delay().toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
            answer.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            exchange.sendResponseHeaders(answer.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
