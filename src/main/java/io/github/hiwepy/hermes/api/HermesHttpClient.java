package io.github.hiwepy.hermes.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hiwepy.hermes.HermesClientConfig;
import static io.github.hiwepy.hermes.api.HermesApiConstants.*;
import io.github.hiwepy.hermes.api.model.*;
import io.github.hiwepy.hermes.exception.HermesHttpException;
import io.github.hiwepy.hermes.util.HermesObjectMapper;
import kong.unirest.core.*;
import kong.unirest.modules.jackson.JacksonObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Hermes Server HTTP 客户端，封装 REST API。
 */
@Slf4j
public class HermesHttpClient implements AutoCloseable {

    private final HermesClientConfig config;
    private final ObjectMapper objectMapper;
    private final UnirestInstance unirest;

    public HermesHttpClient(HermesClientConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.objectMapper = HermesObjectMapper.INSTANCE;
        this.unirest = new UnirestInstance(new Config()
                .connectTimeout(config.getConnectTimeoutMillis())
                .requestTimeout(config.getReadTimeoutMillis())
                .verifySsl(config.isVerifySsl())
                .setObjectMapper(new JacksonObjectMapper(objectMapper)));

        String apiKey = config.resolveApiKey();
        if (!apiKey.isEmpty()) {
            this.unirest.config().setDefaultHeader("Authorization", "Bearer " + apiKey);
        }
    }

    // ============================================================
    // Global / Health
    // ============================================================

    public HealthStatus health() { return get(PATH_HEALTH, HealthStatus.class); }

    public HealthStatus healthDetailed() { return get(PATH_HEALTH_DETAILED, HealthStatus.class); }

    public HealthStatus healthV1() { return get(PATH_V1_HEALTH, HealthStatus.class); }

    // ============================================================
    // Chat Completion
    // ============================================================

    public ChatResponse chatCompletion(ChatRequest request) {
        return post(PATH_CHAT_COMPLETIONS, request, ChatResponse.class);
    }

    public ChatResponse chatCompletion(ChatRequest request, Map<String, String> headers) {
        return post(PATH_CHAT_COMPLETIONS, request, ChatResponse.class, headers);
    }

    // ============================================================
    // Responses API
    // ============================================================

    public ResponseResult createResponse(ResponseRequest request) {
        return post(PATH_RESPONSES, request, ResponseResult.class);
    }

    public ResponseResult createResponse(ResponseRequest request, Map<String, String> headers) {
        return post(PATH_RESPONSES, request, ResponseResult.class, headers);
    }

    public ResponseResult getResponse(String responseId) {
        return get("/v1/responses/" + responseId, ResponseResult.class);
    }

    public boolean deleteResponse(String responseId) {
        HttpResponse<String> resp = unirest.delete(url(PATH_RESPONSES + "/" + responseId)).asString();
        return resp.isSuccess();
    }

    // ============================================================
    // Models & Capabilities
    // ============================================================

    public ModelsResponse listModels() {
        return get(PATH_MODELS, ModelsResponse.class);
    }

    public ModelsResponse.ModelData getModel(String modelId) {
        return get(PATH_MODELS + "/" + java.net.URLEncoder.encode(modelId, java.nio.charset.StandardCharsets.UTF_8),
                ModelsResponse.ModelData.class);
    }

    public CapabilityInfo getCapabilities() { return get(PATH_CAPABILITIES, CapabilityInfo.class); }

    // ============================================================
    // Skills & Toolsets
    // ============================================================

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listSkills() {
        return getList(PATH_SKILLS, new GenericType<List<Map<String, Object>>>() {});
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listToolsets() {
        return getList(PATH_TOOLSETS, new GenericType<List<Map<String, Object>>>() {});
    }

    // ============================================================
    // Run
    // ============================================================

    public RunStatus createRun(RunCreateRequest request) {
        return post(PATH_RUNS, request, RunStatus.class);
    }

    public RunStatus getRun(String runId) { return get("/v1/runs/" + runId, RunStatus.class); }

    public void stopRun(String runId) {
        HttpResponse<String> resp = unirest.post(url(PATH_RUNS + "/" + runId + "/stop")).asString();
        if (!resp.isSuccess()) throw new HermesHttpException(resp.getStatus(), resp.getBody() != null ? resp.getBody() : "");
    }

    public Map<String, Object> approveRun(String runId, Map<String, Object> decision) {
        return postMap(PATH_RUNS + "/" + runId + "/approval", decision);
    }

    // ============================================================
    // Session
    // ============================================================

    public Session createSession(String title) {
        Map<String, Object> body = title != null ? Map.of("title", title) : Map.of();
        return post(PATH_SESSIONS, body, Session.class);
    }

    public List<Session> listSessions() {
        return getList(PATH_SESSIONS, new GenericType<List<Session>>() {});
    }

    /**
     * 分页列出 sessions。
     *
     * @param limit           最大返回条数
     * @param offset          偏移量
     * @param source          来源过滤
     * @param includeChildren 是否包含子 session
     */
    @SuppressWarnings("unchecked")
    public List<Session> listSessions(Integer limit, Integer offset, String source, Boolean includeChildren) {
        GetRequest req = unirest.get(url(PATH_SESSIONS));
        if (limit != null) req.queryString("limit", limit);
        if (offset != null) req.queryString("offset", offset);
        if (source != null) req.queryString("source", source);
        if (includeChildren != null) req.queryString("include_children", includeChildren);
        HttpResponse<List<Session>> resp = req.asObject(new GenericType<List<Session>>() {});
        checkResponse(resp);
        return resp.getBody();
    }

    public Session getSession(String id) { return get(PATH_SESSIONS + "/" + id, Session.class); }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getSessionMessages(String id) {
        return getList(PATH_SESSIONS + "/" + id + "/messages",
                new GenericType<List<Map<String, Object>>>() {});
    }

    public Session forkSession(String id, String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (title != null) body.put("title", title);
        return post(PATH_SESSIONS + "/" + id + "/fork", body, Session.class);
    }

    public boolean deleteSession(String id) {
        HttpResponse<String> resp = unirest.delete(url(PATH_SESSIONS + "/" + id)).asString();
        return resp.isSuccess();
    }

    @SuppressWarnings("unchecked")
    public Session updateSession(String id, Map<String, Object> patch) {
        HttpResponse<Map> resp = unirest.patch(url(PATH_SESSIONS + "/" + id))
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON).body(patch).asObject(Map.class);
        checkResponse(resp);
        return objectMapper.convertValue(resp.getBody(), Session.class);
    }

    public ChatResponse sessionChat(String id, String input) {
        return post(PATH_SESSIONS + "/" + id + "/chat", Map.of("input", input), ChatResponse.class);
    }

    // ============================================================
    // Jobs
    // ============================================================

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listJobs() {
        return getList(PATH_JOBS, new GenericType<List<Map<String, Object>>>() {});
    }

    public Map<String, Object> createJob(Map<String, Object> job) {
        return postMap(PATH_JOBS, job);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getJob(String jobId) {
        HttpResponse<Map> resp = unirest.get(url(PATH_JOBS + "/" + jobId)).asObject(Map.class);
        checkResponse(resp);
        return resp.getBody();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> updateJob(String jobId, Map<String, Object> patch) {
        HttpResponse<Map> resp = unirest.patch(url(PATH_JOBS + "/" + jobId))
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON).body(patch).asObject(Map.class);
        checkResponse(resp);
        return resp.getBody();
    }

    public boolean deleteJob(String jobId) {
        HttpResponse<String> resp = unirest.delete(url(PATH_JOBS + "/" + jobId)).asString();
        if (!resp.isSuccess() && resp.getStatus() != 404) {
            log.warn("deleteJob {} failed: {}", jobId, resp.getStatus());
        }
        return resp.isSuccess();
    }

    public Map<String, Object> pauseJob(String jobId) {
        return postMap("/api/jobs/" + jobId + "/pause", Map.of());
    }

    public Map<String, Object> resumeJob(String jobId) {
        return postMap("/api/jobs/" + jobId + "/resume", Map.of());
    }

    public Map<String, Object> runJobNow(String jobId) {
        return postMap("/api/jobs/" + jobId + "/run", Map.of());
    }

    // ============================================================
    // Hermes-specific headers
    // ============================================================

    /** Build Hermes-specific request headers. */
    public static Map<String, String> hermesHeaders(String sessionKey, String sessionId, String messageChannel) {
        Map<String, String> h = new LinkedHashMap<>();
        if (sessionKey != null) h.put(HEADER_SESSION_KEY, sessionKey);
        if (sessionId != null) h.put(HEADER_SESSION_ID, sessionId);
        if (messageChannel != null) h.put(HEADER_MESSAGE_CHANNEL, messageChannel);
        return h.isEmpty() ? h : h;
    }

    // ============================================================
    // Internal helpers
    // ============================================================

    private String url(String path) {
        return config.getServerUrl() + path;
    }

    private <T> T get(String path, Class<T> type) {
        HttpResponse<T> resp = unirest.get(url(path)).asObject(type);
        checkResponse(resp);
        return resp.getBody();
    }

    @SuppressWarnings("unchecked")
    private <T> T getList(String path, GenericType<T> genericType) {
        HttpResponse<T> resp = unirest.get(url(path)).asObject(genericType);
        checkResponse(resp);
        return resp.getBody();
    }

    private <T> T post(String path, Object body, Class<T> type) {
        HttpResponse<T> resp = unirest.post(url(path))
                .header("Content-Type", "application/json").body(body).asObject(type);
        checkResponse(resp);
        return resp.getBody();
    }

    private <T> T post(String path, Object body, Class<T> type, Map<String, String> headers) {
        HttpRequestWithBody req = unirest.post(url(path))
                .header("Content-Type", "application/json");
        if (headers != null) {
            headers.forEach((k, v) -> { if (v != null) {
                req.header(k, v);
            }});
        }
        HttpResponse<T> resp = req.body(body).asObject(type);
        checkResponse(resp);
        return resp.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postMap(String path, Object body) {
        HttpResponse<Map> resp = unirest.post(url(path))
                .header("Content-Type", "application/json").body(body).asObject(Map.class);
        checkResponse(resp);
        return resp.getBody();
    }

    private <T> void checkResponse(HttpResponse<T> resp) {
        if (!resp.isSuccess()) {
            throw new HermesHttpException(resp.getStatus(),
                    resp.getBody() != null ? resp.getBody().toString() : "");
        }
    }

    @Override
    public void close() { unirest.close(); }
}
