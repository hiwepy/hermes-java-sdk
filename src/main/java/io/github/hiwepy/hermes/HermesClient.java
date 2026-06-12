package io.github.hiwepy.hermes;

import io.github.hiwepy.hermes.api.model.*;
import io.github.hiwepy.hermes.cli.HermesCli;
import io.github.hiwepy.hermes.cli.HermesCliExecutor;
import io.github.hiwepy.hermes.api.HermesHttpClient;
import io.github.hiwepy.hermes.api.HermesSseClient;
import io.github.hiwepy.hermes.api.StreamingResponse;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Hermes 客户端门面：HTTP REST + SSE 事件流 + 本地 CLI。
 */
public class HermesClient implements AutoCloseable {

    private final HermesClientConfig config;
    private final HermesHttpClient httpClient;
    private final HermesSseClient sseClient;
    private final HermesCli cli;

    public HermesClient(HermesClientConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = new HermesHttpClient(config);
        this.sseClient = new HermesSseClient(config);
        this.cli = new HermesCli(new HermesCliExecutor(config));
    }

    public HermesClient(HermesClientConfig config, HermesHttpClient httpClient,
                        HermesSseClient sseClient, HermesCli cli) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.sseClient = Objects.requireNonNull(sseClient, "sseClient");
        this.cli = Objects.requireNonNull(cli, "cli");
    }

    // ============================================================
    // Health
    // ============================================================

    public HealthStatus health() { return httpClient.health(); }
    public HealthStatus healthDetailed() { return httpClient.healthDetailed(); }
    public HealthStatus healthV1() { return httpClient.healthV1(); }

    // ============================================================
    // Chat Completions
    // ============================================================

    public ChatCompletionResponse chatCompletion(ChatCompletionRequest request) {
        return httpClient.chatCompletion(request);
    }

    /** Chat completion with Hermes custom headers. */
    public ChatCompletionResponse chatCompletion(ChatCompletionRequest request,
                                                  Map<String, String> headers) {
        return httpClient.chatCompletion(request, headers);
    }

    /** Convenience: chat with session key. */
    public ChatCompletionResponse chatCompletionWithSession(
            ChatCompletionRequest request, String sessionKey, String sessionId) {
        return httpClient.chatCompletion(request,
                HermesHttpClient.hermesHeaders(sessionKey, sessionId, null));
    }

    // ============================================================
    // Responses API
    // ============================================================

    public ResponseResult createResponse(ResponseRequest request) {
        return httpClient.createResponse(request);
    }

    public ResponseResult createResponse(ResponseRequest request, Map<String, String> headers) {
        return httpClient.createResponse(request, headers);
    }

    public ResponseResult getResponse(String responseId) { return httpClient.getResponse(responseId); }
    public boolean deleteResponse(String responseId) { return httpClient.deleteResponse(responseId); }

    // ============================================================
    // Models & Capabilities & Skills
    // ============================================================

    public ModelsResponse listModels() { return httpClient.listModels(); }
    public CapabilityInfo getCapabilities() { return httpClient.getCapabilities(); }
    public List<Map<String, Object>> listSkills() { return httpClient.listSkills(); }
    public List<Map<String, Object>> listToolsets() { return httpClient.listToolsets(); }

    // ============================================================
    // Run
    // ============================================================

    public RunStatus createRun(RunCreateRequest request) { return httpClient.createRun(request); }
    public RunStatus getRun(String runId) { return httpClient.getRun(runId); }
    public void stopRun(String runId) { httpClient.stopRun(runId); }
    public Map<String, Object> approveRun(String runId, Map<String, Object> decision) {
        return httpClient.approveRun(runId, decision);
    }

    // ============================================================
    // Streaming SSE (chat completions)
    // ============================================================

    /**
     * Streaming chat completion returning a {@link StreamingResponse} that accumulates
     * delta text and completes when the stream ends.
     */
    public StreamingResponse chatCompletionStream(ChatCompletionRequest request) {
        request.setStream(true);
        StreamingResponse stream = new StreamingResponse();
        sseClient.subscribeChat(request, event -> stream.accept(event),
                () -> stream.finish(), stream::fail);
        return stream;
    }

    // ============================================================
    // Session
    // ============================================================

    public Session createSession(String title) { return httpClient.createSession(title); }
    public List<Session> listSessions() { return httpClient.listSessions(); }
    public Session getSession(String sessionId) { return httpClient.getSession(sessionId); }
    public List<Map<String, Object>> getSessionMessages(String id) { return httpClient.getSessionMessages(id); }
    public Session forkSession(String id, String title) { return httpClient.forkSession(id, title); }
    public boolean deleteSession(String sessionId) { return httpClient.deleteSession(sessionId); }
    public ChatCompletionResponse sessionChat(String sessionId, String input) {
        return httpClient.sessionChat(sessionId, input);
    }

    // ============================================================
    // Jobs
    // ============================================================

    public List<Map<String, Object>> listJobs() { return httpClient.listJobs(); }
    public Map<String, Object> createJob(Map<String, Object> job) { return httpClient.createJob(job); }
    public Map<String, Object> getJob(String jobId) { return httpClient.getJob(jobId); }
    public Map<String, Object> updateJob(String jobId, Map<String, Object> patch) { return httpClient.updateJob(jobId, patch); }
    public boolean deleteJob(String jobId) { return httpClient.deleteJob(jobId); }
    public Map<String, Object> pauseJob(String jobId) { return httpClient.pauseJob(jobId); }
    public Map<String, Object> resumeJob(String jobId) { return httpClient.resumeJob(jobId); }
    public Map<String, Object> runJobNow(String jobId) { return httpClient.runJobNow(jobId); }

    // ============================================================
    // SSE (raw access)
    // ============================================================

    public HermesSseClient sse() { return sseClient; }

    // ============================================================
    // CLI
    // ============================================================

    public HermesCli cli() { return cli; }

    // ============================================================
    // Config & lifecycle
    // ============================================================

    public HermesClientConfig getConfig() { return config; }

    @Override
    public void close() { httpClient.close(); sseClient.close(); }
}
