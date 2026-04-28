package com.zhituagent.eval;

import java.util.List;

record BaselineEvalComparisonReport(
        String fixtureName,
        String generatedAt,
        int totalModes,
        List<String> requestedModes,
        List<BaselineEvalResult> modeReports
) {
}
