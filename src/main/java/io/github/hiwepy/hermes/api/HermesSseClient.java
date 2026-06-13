package io.github.hiwepy.hermes.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hiwepy.hermes.HermesClientConfig;
import static io.github.hiwepy.hermes.api.HermesApiConstants.*;
import io.github.hiwepy.hermes.api.model.ChatRequest;
import io.github.hiwepy.hermes.api.model.SseEvent;
import io.github.hiwepy.hermes.util.HermesObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Hermes Server SSE 客户端，消费 run 事件流和 session/chat 流式聊天。
 * <p>每个实例同一时间只允许一个活跃订阅。重复调用 subscribe/subscribeChat 会先停止旧订阅。</p>
 */
@Slf4j
public class HermesSseClient implements AutoCloseable {

    private final HermesClientConfig config;
    private final ObjectMapper mapper;
    private final AtomicReference<Subscription> activeSubscription = new AtomicReference<>();

    public HermesSseClient(HermesClientConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.mapper = HermesObjectMapper.INSTANCE;
    }

    /** 异步订阅 run 事件流。 */
    public void subscribe(String runId, Consumer<SseEvent> consumer) {
        stopCurrent();
        Subscription sub = new Subscription("hermes-sse-run");
        activeSubscription.set(sub);
        sub.executor.submit(() -> doSubscribeRun(runId, consumer, sub));
    }

    /** 返回 BlockingQueue 用于 run 事件流消费。 */
    public BlockingQueue<SseEvent> subscribeQueue(String runId) {
        BlockingQueue<SseEvent> queue = new LinkedBlockingQueue<>();
        subscribe(runId, queue::offer);
        return queue;
    }

    /** 异步订阅 Chat Completion SSE 流式响应（无额外 headers）。 */
    public void subscribeChat(ChatRequest request,
                              Consumer<SseEvent> consumer,
                              Runnable onComplete,
                              Consumer<Throwable> onError) {
        subscribeChat(request, null, consumer, onComplete, onError);
    }

    /** 异步订阅 Chat Completion SSE 流式响应，携带自定义 headers（如 session key）。 */
    public void subscribeChat(ChatRequest request,
                              Map<String, String> headers,
                              Consumer<SseEvent> consumer,
                              Runnable onComplete,
                              Consumer<Throwable> onError) {
        stopCurrent();
        Subscription sub = new Subscription("hermes-sse-chat");
        activeSubscription.set(sub);
        sub.executor.submit(() -> doSubscribeChat(request, headers, consumer, onComplete, onError, sub));
    }

    private void doSubscribeChat(ChatRequest request,
                                 Map<String, String> headers,
                                 Consumer<SseEvent> consumer,
                                 Runnable onComplete,
                                 Consumer<Throwable> onError,
                                 Subscription sub) {
        try {
            String url = config.getServerUrl() + PATH_CHAT_COMPLETIONS;
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            sub.connection = conn;
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(config.getConnectTimeoutMillis());
            conn.setReadTimeout(0);
            conn.setRequestProperty(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON);
            conn.setRequestProperty(HEADER_ACCEPT, MEDIA_TYPE_SSE);
            conn.setRequestProperty(HEADER_CACHE_CONTROL, CACHE_NO_CACHE);
            String apiKey = config.resolveApiKey();
            if (!apiKey.isEmpty()) {
                conn.setRequestProperty(HEADER_AUTHORIZATION, AUTH_BEARER_PREFIX + apiKey);
            }
            if (headers != null) {
                headers.forEach((k, v) -> { if (v != null) conn.setRequestProperty(k, v); });
            }
            String body = mapper.writeValueAsString(request);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            if (status != 200) {
                String errBody = readErrorBody(conn);
                onError.accept(new RuntimeException("SSE chat failed: " + status + " " + errBody));
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                parseSseStream(reader, consumer, onComplete, sub);
            }
            onComplete.run();
        } catch (Exception e) { log.warn("SSE chat error", e); onError.accept(e); }
        finally { sub.executor.shutdown(); }
    }

    /** 异步订阅 session 流式聊天。 */
    public void subscribeSessionStream(String sessionId, String input, Consumer<SseEvent> consumer) {
        stopCurrent();
        Subscription sub = new Subscription("hermes-sse-session");
        activeSubscription.set(sub);
        sub.executor.submit(() -> doSubscribeSessionStream(sessionId, input, consumer, sub));
    }

    // ============================================================
    // Internals
    // ============================================================

    /** 解析 SSE 流，支持 data: 和 event: 行。在 [DONE] 时调用 onComplete 并返回 true。 */
    private boolean parseSseStream(BufferedReader reader, Consumer<SseEvent> consumer,
                                   Runnable onComplete, Subscription sub) throws IOException {
        String currentEvent = null;
        String line;
        while (sub.running && (line = reader.readLine()) != null) {
            if (line.isEmpty()) { currentEvent = null; continue; }

            if (line.startsWith(SSE_EVENT_PREFIX)) {
                currentEvent = line.substring(SSE_EVENT_PREFIX.length()).trim();
                continue;
            }
            if (line.startsWith(SSE_DATA_PREFIX)) {
                String json = line.substring(SSE_DATA_PREFIX.length()).trim();
                if (SSE_DONE_MARKER.equals(json)) { onComplete.run(); return true; }
                if (!json.isEmpty()) {
                    try {
                        SseEvent event = mapper.readValue(json, SseEvent.class);
                        event.setEvent(currentEvent);
                        consumer.accept(event);
                    } catch (Exception e) { log.debug("SSE parse: {}", json, e); }
                }
            }
        }
        return false;
    }

    private void doSubscribeRun(String runId, Consumer<SseEvent> consumer, Subscription sub) {
        while (sub.running) {
            try {
                String url = config.getServerUrl() + PATH_RUNS + "/" + runId + "/events";
                HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
                sub.connection = conn;
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(config.getConnectTimeoutMillis());
                conn.setReadTimeout(0);
                conn.setRequestProperty(HEADER_ACCEPT, MEDIA_TYPE_SSE);
                conn.setRequestProperty(HEADER_CACHE_CONTROL, CACHE_NO_CACHE);
                String apiKey = config.resolveApiKey();
                if (!apiKey.isEmpty()) conn.setRequestProperty(HEADER_AUTHORIZATION, AUTH_BEARER_PREFIX + apiKey);
                int status = conn.getResponseCode();
                if (status != 200) {
                    String errBody = readErrorBody(conn);
                    log.warn("SSE run events failed status={} body={}, retrying", status, errBody);
                    Thread.sleep(DEFAULT_CONNECT_TIMEOUT_MS / 3);
                    continue;
                }
                log.info("SSE connected to {}", url);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    parseSseStream(reader, consumer, () -> {}, sub);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            catch (IOException e) {
                if (sub.running) {
                    log.warn("SSE connection lost, retrying", e);
                    try { Thread.sleep(DEFAULT_CONNECT_TIMEOUT_MS / 3); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            } finally {
                if (sub.connection != null) { sub.connection.disconnect(); sub.connection = null; }
            }
        }
    }

    private void doSubscribeSessionStream(String sessionId, String input, Consumer<SseEvent> consumer, Subscription sub) {
        while (sub.running) {
            try {
                String url = config.getServerUrl() + PATH_SESSIONS + "/" + sessionId + "/chat/stream";
                HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
                sub.connection = conn;
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(config.getConnectTimeoutMillis());
                conn.setReadTimeout(0);
                conn.setRequestProperty(HEADER_ACCEPT, MEDIA_TYPE_SSE);
                conn.setRequestProperty(HEADER_CACHE_CONTROL, CACHE_NO_CACHE);
                conn.setRequestProperty(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON);
                String apiKey = config.resolveApiKey();
                if (!apiKey.isEmpty()) conn.setRequestProperty(HEADER_AUTHORIZATION, AUTH_BEARER_PREFIX + apiKey);
                String body = mapper.writeValueAsString(java.util.Map.of("input", input));
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                int status = conn.getResponseCode();
                if (status != 200) {
                    String errBody = readErrorBody(conn);
                    log.warn("SSE session stream failed status={} body={}, retrying", status, errBody);
                    Thread.sleep(DEFAULT_CONNECT_TIMEOUT_MS / 3);
                    continue;
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    parseSseStream(reader, consumer, () -> {}, sub);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            catch (IOException e) {
                if (sub.running) {
                    log.warn("SSE session stream lost, retrying", e);
                    try { Thread.sleep(DEFAULT_CONNECT_TIMEOUT_MS / 3); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            } finally {
                if (sub.connection != null) { sub.connection.disconnect(); sub.connection = null; }
            }
        }
    }

    private static String readErrorBody(HttpURLConnection conn) {
        try {
            java.io.InputStream es = conn.getErrorStream();
            if (es == null) return "";
            Scanner s = new Scanner(es, "UTF-8").useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        } catch (Exception e) { return ""; }
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    public void stop() {
        stopCurrent();
    }

    private void stopCurrent() {
        Subscription old = activeSubscription.getAndSet(null);
        if (old != null) old.stop();
    }

    @Override
    public void close() { stop(); }

    /** Per-subscription state so multiple subscriptions don't share mutable fields. */
    private static class Subscription {
        volatile boolean running = true;
        volatile HttpURLConnection connection;
        final ExecutorService executor;

        Subscription(String name) {
            this.executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            });
        }

        void stop() {
            running = false;
            if (connection != null) connection.disconnect();
            executor.shutdownNow();
        }
    }
}
