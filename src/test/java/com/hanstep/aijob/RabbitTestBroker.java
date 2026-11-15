package com.hanstep.aijob;

import com.hanstep.aijob.messaging.AiJobRoute;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI 작업 발행 테스트용 RabbitMQ(compose 와 같은 rabbitmq:4-management). JVM 에서 한 번 띄우고 끝나면 Testcontainers 가 지운다.
 * compose 브로커에 테스트 메시지가 들어가 worker 가 처리하지 않도록 따로 둔다. Docker 가 없으면 이 테스트들은 실패한다.
 */
public final class RabbitTestBroker {

    public static final String WORK_QUEUE = AiJobRoute.POLICY_INGEST.routingKey();
    public static final String RETRY_QUEUE = AiJobRoute.POLICY_INGEST.retryQueue();
    public static final String DEAD_LETTER_QUEUE = AiJobRoute.POLICY_INGEST.deadLetterQueue();

    private static final RabbitMQContainer CONTAINER = new RabbitMQContainer("rabbitmq:4-management");
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static {
        CONTAINER.start();
    }

    private RabbitTestBroker() {
    }

    /** .env 의 compose 접속 정보 대신 이 컨테이너를 쓰게 한다. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", CONTAINER::getHost);
        registry.add("spring.rabbitmq.port", CONTAINER::getAmqpPort);
        registry.add("spring.rabbitmq.username", CONTAINER::getAdminUsername);
        registry.add("spring.rabbitmq.password", CONTAINER::getAdminPassword);
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
    }

    /** 관리 API(GET /api/...) 응답. 큐·exchange 의 실제 선언 값(durable, arguments)을 본다. */
    public static JsonNode managementApi(String path) throws Exception {
        String auth = CONTAINER.getAdminUsername() + ":" + CONTAINER.getAdminPassword();
        HttpRequest request = HttpRequest.newBuilder(URI.create(CONTAINER.getHttpUrl() + "/api/" + path))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8)))
                .GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("management api " + path + " -> " + response.statusCode());
        }
        return MAPPER.readTree(response.body());
    }

    public static JsonNode queue(String name) throws Exception {
        return managementApi("queues/%2F/" + URLEncoder.encode(name, StandardCharsets.UTF_8));
    }
}
