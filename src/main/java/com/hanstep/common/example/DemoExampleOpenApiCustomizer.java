package com.hanstep.common.example;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.Map;
import java.util.function.Supplier;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Swagger 의 Example Value 를 데모 페르소나 전체 값으로 채움.
 *
 * <p>필드별 @Schema(example) 만으로는 배열이 항목 1개짜리로 보이므로, FE 가 화면에 그대로 쓸 수 있게
 * 요청·응답 예시를 통째로 넣음. 실제 응답과 같은 ObjectMapper 로 직렬화해 날짜·필드 이름이 응답과 똑같음.
 * DemoExamples 를 지울 때 함께 지움.
 */
@Component
public class DemoExampleOpenApiCustomizer implements OpenApiCustomizer {

    private static final Map<String, Supplier<Object>> RESPONSES = Map.ofEntries(
            Map.entry("GET /api/v1/onboarding", DemoExamples::onboarding),
            Map.entry("PUT /api/v1/onboarding/profile", DemoExamples::profile),
            Map.entry("POST /api/v1/onboarding/steps/{stepCode}", DemoExamples::stepResponse),
            Map.entry("GET /api/v1/onboarding/summary", DemoExamples::summary),
            Map.entry("GET /api/v1/consents", DemoExamples::consents),
            Map.entry("POST /api/v1/consents", DemoExamples::savedMydataConsents),
            Map.entry("GET /api/v1/mydata/institutions", DemoExamples::institutions),
            Map.entry("POST /api/v1/mydata/connections", DemoExamples::connections),
            Map.entry("GET /api/v1/mydata/connections", DemoExamples::connections),
            Map.entry("GET /api/v1/goals", DemoExamples::goals),
            Map.entry("PUT /api/v1/goals", DemoExamples::goals),
            Map.entry("GET /api/v1/commitments", DemoExamples::commitments),
            Map.entry("PUT /api/v1/commitments", DemoExamples::commitments),
            Map.entry("GET /api/v1/commitments/remittance-candidates", DemoExamples::remittanceCandidates));

    private static final Map<String, Supplier<Object>> REQUESTS = Map.ofEntries(
            Map.entry("PUT /api/v1/onboarding/profile", DemoExamples::profileRequest),
            Map.entry("POST /api/v1/onboarding/steps/{stepCode}", DemoExamples::stepRequest),
            Map.entry("POST /api/v1/consents", DemoExamples::consentRequest),
            Map.entry("POST /api/v1/mydata/connections", DemoExamples::connectionRequest),
            Map.entry("PUT /api/v1/goals", DemoExamples::goalRequest),
            Map.entry("PUT /api/v1/commitments", DemoExamples::commitmentRequest));

    private final ObjectMapper objectMapper;

    public DemoExampleOpenApiCustomizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            String key = method.name() + " " + path;
            Supplier<Object> response = RESPONSES.get(key);
            if (response != null) {
                setResponseExample(operation, response.get());
            }
            Supplier<Object> request = REQUESTS.get(key);
            if (request != null && operation.getRequestBody() != null) {
                setExample(operation.getRequestBody().getContent(), request.get());
            }
        }));
    }

    private void setResponseExample(Operation operation, Object value) {
        if (operation.getResponses() == null) {
            return;
        }
        for (String code : new String[] {"200", "201"}) {
            ApiResponse response = operation.getResponses().get(code);
            // 201 이 있으면 200 은 Idempotency-Key 재요청 설명용이라 content 가 없음
            if (response != null && response.getContent() != null) {
                setExample(response.getContent(), value);
            }
        }
    }

    private void setExample(Content content, Object value) {
        if (content == null) {
            return;
        }
        // 실제 응답과 같은 직렬화 결과(Map/List)로 바꿔 넣음
        Object json = objectMapper.readValue(objectMapper.writeValueAsString(value), Object.class);
        content.values().forEach(mediaType -> mediaType.setExample(json));
    }
}
