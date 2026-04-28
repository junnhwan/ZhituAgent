package com.zhituagent.eval;

import com.zhituagent.config.EvalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
@ConditionalOnProperty(prefix = "zhitu.eval", name = "enabled", havingValue = "true")
public class EvalApplicationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalApplicationRunner.class);
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final BaselineEvalRunner baselineEvalRunner;
    private final EvalProperties evalProperties;
    private final ConfigurableApplicationContext applicationContext;

    public EvalApplicationRunner(BaselineEvalRunner baselineEvalRunner,
                                 EvalProperties evalProperties,
                                 ConfigurableApplicationContext applicationContext) {
        this.baselineEvalRunner = baselineEvalRunner;
        this.evalProperties = evalProperties;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path reportPath = Path.of(
                evalProperties.getReportDir(),
                "baseline-comparison-" + FILE_TS.format(LocalDateTime.now()) + ".json"
        );
        BaselineEvalComparisonReport report = baselineEvalRunner.runModeComparisonFixture(evalProperties.getModes(), reportPath);
        log.info(
                "评估报告已生成 eval.completed totalModes={} reportPath={} requestedModes={}",
                report.totalModes(),
                reportPath.toAbsolutePath(),
                String.join(",", report.requestedModes())
        );

        if (evalProperties.isExitAfterRun()) {
            SpringApplication.exit(applicationContext, () -> 0);
        }
    }
}
