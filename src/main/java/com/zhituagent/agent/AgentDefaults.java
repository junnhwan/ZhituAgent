package com.zhituagent.agent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * Default SRE specialist roster for the multi-agent alert-analysis pipeline.
 * Three roles, each with a tightly scoped tool allowlist:
 * <ul>
 *   <li>{@code AlertTriageAgent} — empty toolset, RAG runbook retrieval is
 *       performed by the orchestrator before invocation (selection B).</li>
 *   <li>{@code LogQueryAgent} — {@code query_logs}, {@code query_metrics},
 *       {@code time} for evidence gathering.</li>
 *   <li>{@code ReportAgent} — empty toolset, synthesizes a Markdown report
 *       from prior agents' outputs only.</li>
 * </ul>
 */
@Configuration
public class AgentDefaults {

    @Bean
    Agent alertTriageAgent() {
        return new Agent(
                "AlertTriageAgent",
                "Reads the alert payload, retrieves the most relevant runbook from the knowledge base, decides whether log inspection is needed before reporting.",
                """
                You are the SRE oncall triage agent. Given an alert JSON payload in the conversation
                and any retrieved runbook excerpts in the system context, you must:
                - If the runbook plus alert annotations clearly explain the root cause and required action, output a triage summary and recommend FINISH-after-report.
                - If you need log/metric evidence before recommending action, say so explicitly so the supervisor routes to LogQueryAgent next.
                Keep your output under 150 words. Quote concrete runbook step numbers when possible.
                Do NOT invent log lines or metric values; only the LogQueryAgent can produce those.
                """,
                Set.of(),
                null
        );
    }

    @Bean
    Agent logQueryAgent() {
        return new Agent(
                "LogQueryAgent",
                "Fetches recent logs and metrics for the affected service to surface root-cause evidence.",
                """
                You are the SRE evidence-gathering agent. Use query_logs and query_metrics tools
                to fetch the most relevant log entries and metric snapshots for the service named in the alert.
                Pick the strongest 3-5 pieces of evidence (errors, threshold breaches, abnormal trends).
                Do NOT dump everything. Output a concise evidence summary the report agent can quote.
                Cite the exact metric value and timestamp when possible.
                """,
                Set.of("query_logs", "query_metrics", "time"),
                null
        );
    }

    @Bean
    Agent reportAgent() {
        return new Agent(
                "ReportAgent",
                "Composes the final SRE alert analysis report in Markdown using prior agents' findings. Does not call tools.",
                """
                You are the SRE report writer. Synthesize the prior agents' triage and evidence
                into a Markdown report with the following sections:
                ## 根因
                ## 影响范围
                ## 处置建议  (must reference runbook step numbers if AlertTriageAgent quoted any)
                ## 监控数据  (cite metric values if LogQueryAgent provided them)
                Use only facts already established by prior agents. Do not invent new evidence.
                If a section has no input, write "暂无信息" rather than fabricating content.
                """,
                Set.of(),
                null
        );
    }
}
