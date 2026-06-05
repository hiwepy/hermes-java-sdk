package io.github.hiwepy.hermes.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hiwepy.hermes.HermesClientConfig;
import io.github.hiwepy.hermes.exception.HermesHttpException;
import io.github.hiwepy.hermes.model.*;
import kong.unirest.core.*;
import kong.unirest.modules.jackson.JacksonObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Hermes Server HTTP 客户端，封装 REST API。
 */
public class HermesHttpClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HermesHttpClient.class);

    private final HermesClientConfig config;
    private final UnirestInstance unirest;

    public HermesHttpClient(HermesClientConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.unirest = new UnirestInstance(new Config()
                .connectTimeout(config.getConnectTimeoutMillis())
                .requestTimeout(config.getReadTimeoutMillis())
                .verifySsl(config.isVerifySsl())
                .setObjectMapper(new JacksonObjectMapper(mapper)));

        String apiKey = config.resolveApiKey();
        if (!apiKey.isEmpty()) {
            this.unirest.config().setDefaultHeader("Authorization", "Bearer " + apiKey);
        }
    }

    // ============================================================
    // Global
    // ============================================================

    public HealthStatus health() {
        return get("/health", HealthStatus.class);
    }

    // ============================================================
    // Run
    // ============================================================

    public RunStatus createRun(RunCreateRequest request) {
        return post("/v1/runs", request, RunStatus.class);
    }

    public RunStatus getRun(String runId) {
        return get("/v1/runs/" + runId, RunStatus.class);
    }

    public void stopRun(String runId) {
        HttpResponse<String> resp = unirest.post(url("/v1/runs/" + runId + "/stop")).asString();
        if (!resp.isSuccess()) {
            throw new HermesHttpException(resp.getStatus(),
                    resp.getBody() != null ? resp.getBody() : "");
        }
    }

    // ============================================================
    // Chat Completion
    // ============================================================

    public ChatCompletionResponse chatCompletion(ChatCompletionRequest request) {
        return post("/v1/chat/completions", request, ChatCompletionResponse.class);
    }

    // ============================================================
    // Session
    // ============================================================

    public Session createSession(String title) {
        Map<String, Object> body = title != null ? Map.of("title", title) : Map.of();
        return post("/api/sessions", body, Session.class);
    }

    public List<Session> listSessions() {
        return getList("/api/sessions", new GenericType<List<Session>>() {});
    }

    public Session getSession(String id) {
        return get("/api/sessions/" + id, Session.class);
    }

    public boolean deleteSession(String id) {
        HttpResponse<String> resp = unirest.delete(url("/api/sessions/" + id)).asString();
        return resp.isSuccess();
    }

    public ChatCompletionResponse sessionChat(String id, String input) {
        Map<String, Object> body = Map.of("input", input);
        return post("/api/sessions/" + id + "/chat", body, ChatCompletionResponse.class);
    }

    // ============================================================
    // Model & Capabilities
    // ============================================================

    public ModelInfo listModels() {
        return get("/v1/models", ModelInfo.class);
    }

    public CapabilityInfo getCapabilities() {
        return get("/v1/capabilities", CapabilityInfo.class);
    }

    // ============================================================
    // Internal helpers
    // ============================================================

    private String url(String path) {
        return config.getServerUrl() + path;
    }

    private <T> T get(String path, Class<T> type) {
        HttpResponse<T> resp = unirest.get(url(path)).asObject(type);
        if (!resp.isSuccess()) {
            throw new HermesHttpException(resp.getStatus(),
                    resp.getBody() != null ? resp.getBody().toString() : "");
        }
        return resp.getBody();
    }

    private <T> T getList(String path, GenericType<T> genericType) {
        HttpResponse<T> resp = unirest.get(url(path)).asObject(genericType);
        if (!resp.isSuccess()) {
            throw new HermesHttpException(resp.getStatus(),
                    resp.getBody() != null ? resp.getBody().toString() : "");
        }
        return resp.getBody();
    }

    private <T> T post(String path, Object body, Class<T> type) {
        HttpResponse<T> resp = unirest.post(url(path))
                .header("Content-Type", "application/json")
                .body(body)
                .asObject(type);
        if (!resp.isSuccess()) {
            throw new HermesHttpException(resp.getStatus(),
                    resp.getBody() != null ? resp.getBody().toString() : "");
        }
        return resp.getBody();
    }

    @Override
    public void close() {
        unirest.close();
    }
}
