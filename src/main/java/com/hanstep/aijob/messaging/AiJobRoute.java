package com.hanstep.aijob.messaging;

import com.hanstep.aijob.domain.AiJobType;
import java.util.Arrays;

/** 큐 선언과 발행에서 같은 경로를 쓴다. OCR 연결 시 여기에 경로를 추가한다. */
public enum AiJobRoute {
    POLICY_INGEST(AiJobType.POLICY_INGEST, "policy.ingest");

    public static final String EXCHANGE = "hanstep.ai";
    private final AiJobType jobType;
    private final String routingKey;

    AiJobRoute(AiJobType jobType, String routingKey) {
        this.jobType = jobType;
        this.routingKey = routingKey;
    }

    public String routingKey() { return routingKey; }
    public String retryQueue() { return routingKey + ".retry"; }
    public String deadLetterQueue() { return routingKey + ".dlq"; }

    public static AiJobRoute forType(AiJobType type) {
        return Arrays.stream(values()).filter(route -> route.jobType == type).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported AI job type: " + type));
    }
}
