package com.zhituagent.mcp.client;

import com.zhituagent.mcp.McpCallResult;
import com.zhituagent.mcp.McpProperties;
import com.zhituagent.mcp.McpToolSpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-protocol integration test for {@link OfficialSdkMcpClient}. Spawns the
 * official MCP reference server {@code @modelcontextprotocol/server-everything}
 * via {@code npx} (no API key needed) and asserts the JSON-RPC handshake +
 * {@code tools/list} + {@code tools/call} round-trip actually move bytes over
 * stdio — the missing piece in {@code McpToolRegistrarTest} which only ever
 * exercised {@link com.zhituagent.mcp.MockMcpClient}.
 *
 * <p>Skipped automatically when {@code npx} is not on PATH (no Node.js installed
 * locally / CI without npm) — see {@link #assumeNpxAvailable()}.
 *
 * <p>Tagged {@code integration} so {@code mvn test} (surefire) excludes it and
 * only {@code mvn verify} (failsafe) picks it up — matches the project's
 * existing IT split (see {@code KafkaPipelineIntegrationTest}).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OfficialSdkMcpClientIntegrationTest {

    private OfficialSdkMcpClient client;

    @BeforeAll
    static void assumeNpxAvailable() {
        Assumptions.assumeTrue(npxAvailable(),
                "npx not available on PATH — skipping MCP real-protocol IT");
    }

    @BeforeAll
    void connect() {
        McpProperties.ServerConfig cfg = new McpProperties.ServerConfig();
        cfg.setName("everything");
        cfg.setTransport("stdio");
        cfg.setCommand(npxCommand());
        cfg.setArgs(List.of("-y", "@modelcontextprotocol/server-everything"));
        client = new OfficialSdkMcpClient(cfg);
    }

    @AfterAll
    void close() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void listTools_returnsAtLeastOneTool() {
        List<McpToolSpec> tools = client.listTools();
        assertThat(tools)
                .as("server-everything should expose at least one demo tool")
                .isNotEmpty();
        // server-everything bundles `echo`, `add`, `longRunningOperation`, etc.
        assertThat(tools).anyMatch(t -> t.name().equals("echo"));
    }

    @Test
    void callTool_echo_returnsInputText() {
        McpCallResult result = client.callTool("echo", Map.of("message", "hi-mcp-it"));
        assertThat(result.isError()).isFalse();
        assertThat(result.content())
                .as("echo tool should return the input text in its content")
                .contains("hi-mcp-it");
        assertThat(result.metadata())
                .containsEntry("server", "everything")
                .containsEntry("transport", "stdio");
    }

    private static String npxCommand() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows")
                ? "npx.cmd"
                : "npx";
    }

    private static boolean npxAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(npxCommand(), "-v");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean exited = p.waitFor(5, TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}
