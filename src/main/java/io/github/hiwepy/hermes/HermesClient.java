package io.github.hiwepy.hermes;

import io.github.hiwepy.hermes.cli.HermesCli;
import io.github.hiwepy.hermes.cli.HermesCliExecutor;
import io.github.hiwepy.hermes.http.HermesHttpClient;
import io.github.hiwepy.hermes.http.HermesSseClient;
import io.github.hiwepy.hermes.model.*;

import java.util.List;
import java.util.Objects;

/**
 * Hermes 客户端门面：HTTP API Server + SSE 事件流 + 本地 CLI。
 * <p>
 * 三条通信通道相互独立：
 * </p>
 * <ul>
 *     <li><b>HTTP</b>：{@link #createRun} / {@link #chatCompletion} / {@link #createSession} 等 — REST API</li>
 *     <li><b>SSE</b>：{@link #sse()} — 事件流</li>
 *     <li><b>CLI</b>：{@link #cli()} — 本地 {@code hermes} 命令封装</li>
 * </ul>
 */
public class HermesClient implements AutoCloseable {

    private final HermesClientConfig config;
    private final HermesHttpClient httpClient;
    private final HermesSseClient sseClient;
    private final HermesCli cli;

    /**
     * 标准构造（自动创建 HTTP、SSE、CLI 客户端）。
     */
    public HermesClient(HermesClientConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = new HermesHttpClient(config);
        this.sseClient = new HermesSseClient(config);
        this.cli = new HermesCli(new HermesCliExecutor(config));
    }

    /**
     * 完整依赖注入（用于测试或自定义组件）。
     */
    public HermesClient(HermesClientConfig config,
                        HermesHttpClient httpClient,
                        HermesSseClient sseClient,
                        HermesCli cli) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.sseClient = Objects.requireNonNull(sseClient, "sseClient");
        this.cli = Objects.requireNonNull(cli, "cli");
    }

    // ============================================================
    // Run
    // ============================================================

    /**
     * 创建一个新的 run。
     */
    public RunStatus createRun(RunCreateRequest request) {
        return httpClient.createRun(request);
    }

    /**
     * 获取 run 详情。
     */
    public RunStatus getRun(String runId) {
        return httpClient.getRun(runId);
    }

    /**
     * 停止正在运行的 run。
     */
    public void stopRun(String runId) {
        httpClient.stopRun(runId);
    }

    // ============================================================
    // Chat Completion
    // ============================================================

    /**
     * 发送 chat completion 请求并同步等待 AI 响应。
     */
    public ChatCompletionResponse chatCompletion(ChatCompletionRequest request) {
        return httpClient.chatCompletion(request);
    }

    // ============================================================
    // Session
    // ============================================================

    /**
     * 创建一个新的 session。
     */
    public Session createSession(String title) {
        return httpClient.createSession(title);
    }

    /**
     * 列出所有 session。
     */
    public List<Session> listSessions() {
        return httpClient.listSessions();
    }

    /**
     * 获取 session 详情。
     */
    public Session getSession(String sessionId) {
        return httpClient.getSession(sessionId);
    }

    /**
     * 删除 session。
     */
    public boolean deleteSession(String sessionId) {
        return httpClient.deleteSession(sessionId);
    }

    /**
     * 在 session 中发送 chat 消息。
     */
    public ChatCompletionResponse sessionChat(String sessionId, String input) {
        return httpClient.sessionChat(sessionId, input);
    }

    // ============================================================
    // Models & Capabilities
    // ============================================================

    /**
     * 列出可用模型。
     */
    public ModelInfo listModels() {
        return httpClient.listModels();
    }

    /**
     * 获取系统能力描述。
     */
    public CapabilityInfo getCapabilities() {
        return httpClient.getCapabilities();
    }

    // ============================================================
    // Global
    // ============================================================

    /**
     * 健康检查。
     */
    public HealthStatus health() {
        return httpClient.health();
    }

    // ============================================================
    // SSE 事件流
    // ============================================================

    /**
     * 获取 SSE 客户端实例。
     */
    public HermesSseClient sse() {
        return sseClient;
    }

    // ============================================================
    // CLI
    // ============================================================

    /**
     * 本地 CLI 命令封装。
     */
    public HermesCli cli() {
        return cli;
    }

    // ============================================================
    // Config
    // ============================================================

    public HermesClientConfig getConfig() {
        return config;
    }

    // ============================================================
    // 生命周期
    // ============================================================

    @Override
    public void close() {
        httpClient.close();
        sseClient.close();
    }
}
