package com.zhituagent.rag;

import java.util.List;

public interface RerankClient {

    RerankResponse rerank(String query, List<RetrievalCandidate> candidates, int topN);

    record RerankResponse(
            String model,
            List<RerankResult> results
    ) {
    }

    record RerankResult(
            int index,
            double score
    ) {
    }
}
