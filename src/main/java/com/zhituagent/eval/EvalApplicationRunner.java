package com.zhituagent.eval;

import com.zhituagent.config.EvalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
@ConditionalOnExpression("${zhitu.eval.enabled:false} or ${zhitu.eval.cmteb.enabled:false}")
public class EvalApplicationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalApplicationRunner.class);
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final BaselineEvalRunner baselineEvalRunner;
    private final BaselineComparisonReporter comparisonReporter;
    private final EvalProperties evalProperties;
    private final ConfigurableApplicationContext applicationContext;
    private final CmtebEvalRunner cmtebEvalRunner;

    public EvalApplicationRunner(BaselineEvalRunner baselineEvalRunner,
                                 BaselineComparisonReporter comparisonReporter,
                                 EvalProperties evalProperties,
                                 ConfigurableApplicationContext applicationContext,
                                 @Autowired(required = false) CmtebEvalRunner cmtebEvalRunner) {
        this.baselineEvalRunner = baselineEvalRunner;
        this.comparisonReporter = comparisonReporter;
        this.evalProperties = evalProperties;
        this.applicationContext = applicationContext;
        this.cmtebEvalRunner = cmtebEvalRunner;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            if (evalProperties.getCmteb().isEnabled()) {
                runCmteb();
            } else if (!evalProperties.getCompareLabels().isEmpty()) {
                runComparison();
            } else {
                runFixture();
            }
        } finally {
            if (evalProperties.isExitAfterRun()) {
                // SpringApplication.exit returns once the context is closed, but the JVM may
                // hang if non-daemon threads (Redis pool, LangChain HTTP client, Kafka producer)
                // are still alive. Force-exit so the next sweep group can claim embedding API
                // connection quotas without deadlocking on zombie pools.
                int code = SpringApplication.exit(applicationContext, () -> 0);
                System.exit(code);
            }
        }
    }

    private void runCmteb() throws Exception {
        if (cmtebEvalRunner == null) {
            throw new IllegalStateException("zhitu.eval.cmteb.enabled=true but CmtebEvalRunner bean missing");
        }
        String label = evalProperties.getCmteb().getLabel();
        Path reportPath = Path.of(
                evalProperties.getReportDir(),
                "cmteb-" + label + "-" + FILE_TS.format(LocalDateTime.now()) + ".json"
        );
        CmtebEvalRunner.Report report = cmtebEvalRunner.run(reportPath);
        log.info(
                "C-MTEB 评估报告已生成 cmteb.completed label={} corpusSize={} queries={} ndcg@{}={} reportPath={}",
                label,
                report.corpusSize(),
                report.queryCount(),
                report.topK(),
                String.format(java.util.Locale.ROOT, "%.4f", report.meanNdcgAtTopK()),
                reportPath.toAbsolutePath()
        );
    }

    private void runFixture() throws Exception {
        String label = evalProperties.getLabel();
        String labelSegment = label.isBlank() ? "comparison" : label;
        Path reportPath = Path.of(
                evalProperties.getReportDir(),
                "baseline-" + labelSegment + "-" + FILE_TS.format(LocalDateTime.now()) + ".json"
        );
        BaselineEvalComparisonReport report = baselineEvalRunner.runModeComparisonFixture(evalProperties.getModes(), reportPath);
        log.info(
                "评估报告已生成 eval.completed label={} totalModes={} reportPath={} requestedModes={}",
                labelSegment,
                report.totalModes(),
                reportPath.toAbsolutePath(),
                String.join(",", report.requestedModes())
        );
    }

    private void runComparison() throws Exception {
        String tsSuffix = FILE_TS.format(LocalDateTime.now());
        String joinedLabels = String.join("-vs-", evalProperties.getCompareLabels());
        Path jsonPath = Path.of(
                evalProperties.getReportDir(),
                "baseline-compare-" + joinedLabels + "-" + tsSuffix + ".json"
        );
        Path markdownPath = Path.of(
                evalProperties.getReportDir(),
                "baseline-compare-" + joinedLabels + "-" + tsSuffix + ".md"
        );
        comparisonReporter.compareLatest(
                Path.of(evalProperties.getReportDir()),
                evalProperties.getCompareLabels(),
                jsonPath,
                markdownPath
        );
        log.info(
                "对比报告已生成 eval.compare.completed labels={} jsonPath={} markdownPath={}",
                joinedLabels,
                jsonPath.toAbsolutePath(),
                markdownPath.toAbsolutePath()
        );
    }
}
