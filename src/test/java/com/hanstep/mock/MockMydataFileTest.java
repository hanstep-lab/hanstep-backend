package com.hanstep.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 마이데이터 Mock 원천 파일(src/main/resources/mock/mydata) 형식 검증.
 * 시연 페르소나 값(DemoExamples)과 DB 칸(financial_transactions 등)에 맞는지 본다. 점수 계산은 하지 않는다.
 */
class MockMydataFileTest {

    private static final String DIR = "/mock/mydata/demo-e9-nguyen/";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    // 02_hanstep_seed.sql 의 transaction_categories.code, financial_transactions.transaction_kind ENUM
    private static final Set<String> CATEGORY_CODES = Set.of("INCOME", "SALARY", "OTHER_INCOME", "EXPENSE",
            "HOUSING", "UTILITY", "TELECOM", "FOOD", "TRANSPORT", "LIVING", "REMITTANCE", "LOAN_PAYMENT", "OTHER",
            "TRANSFER", "SAVINGS", "CARD_PAYMENT");
    private static final Set<String> TRANSACTION_KINDS = Set.of("NORMAL", "OWN_TRANSFER", "SAVINGS_TRANSFER",
            "LOAN_DISBURSEMENT", "LOAN_REPAYMENT", "CARD_SETTLEMENT");

    @Test
    void 은행_거래는_12개월_매달_급여_송금_공과금이_있다() throws IOException {
        List<JsonNode> txns = list(read("bank-shinhan.json").path("account_list").get(0).path("trans_list"));

        Map<String, int[]> byMonth = new TreeMap<>(); // [급여, 송금, 가스, 전기·수도, 월세]
        Set<String> salaryPayers = new HashSet<>();
        for (JsonNode t : txns) {
            int[] c = byMonth.computeIfAbsent(t.path("trans_dtime").asString().substring(0, 6), k -> new int[5]);
            String category = t.path("category_code").asString();
            String counterparty = t.path("counterparty_name").asString();
            switch (category) {
                case "SALARY" -> {
                    c[0]++;
                    salaryPayers.add(counterparty);
                    assertThat(t.path("trans_amt").asLong()).isEqualTo(2_350_000);
                }
                case "REMITTANCE" -> c[1]++;
                case "UTILITY" -> c[counterparty.contains("도시가스") ? 2 : 3]++;
                case "HOUSING" -> c[4]++;
                default -> { }
            }
        }

        assertThat(byMonth).hasSize(12);
        byMonth.values().forEach(c -> assertThat(c).containsExactly(1, 2, 1, 2, 1));
        assertThat(salaryPayers).as("급여는 매달 같은 회사").hasSize(1);
    }

    @Test
    void 송금은_어머니_50만_본인_20만() throws IOException {
        List<JsonNode> txns = list(read("bank-shinhan.json").path("account_list").get(0).path("trans_list"));

        Map<String, Long> sumByName = new TreeMap<>();
        txns.stream().filter(t -> "REMITTANCE".equals(t.path("category_code").asString()))
                .forEach(t -> sumByName.merge(t.path("counterparty_name").asString(),
                        t.path("trans_amt").asLong(), Long::sum));

        assertThat(sumByName).containsEntry("NGUYEN THI MAI", 500_000L * 12)
                .containsEntry("NGUYEN VAN MINH", 200_000L * 12);
    }

    @Test
    void 카드_사용_월평균은_약_45만() throws IOException {
        List<JsonNode> approvals = list(read("card-shinhan.json").path("card_list").get(0).path("approval_list"));

        Map<String, Long> byMonth = new TreeMap<>();
        approvals.forEach(a -> byMonth.merge(a.path("approved_dtime").asString().substring(0, 6),
                a.path("approved_amt").asLong(), Long::sum));
        double avg = byMonth.values().stream().mapToLong(Long::longValue).average().orElse(0);

        assertThat(byMonth).hasSize(12);
        assertThat(avg).isBetween(430_000.0, 470_000.0);
    }

    @Test
    void 거래_키는_유일하고_DB_칸_값을_벗어나지_않는다() throws IOException {
        List<JsonNode> all = new ArrayList<>();
        all.addAll(list(read("bank-shinhan.json").path("account_list").get(0).path("trans_list")));
        all.addAll(list(read("card-shinhan.json").path("card_list").get(0).path("approval_list")));

        Set<String> keys = new HashSet<>();
        for (JsonNode t : all) {
            String key = t.has("trans_key") ? t.path("trans_key").asString() : t.path("approved_key").asString();
            assertThat(keys.add(key)).as("중복 키 %s", key).isTrue();
            assertThat(key.length()).isLessThanOrEqualTo(100); // external_txn_key VARCHAR(100)
            assertThat(CATEGORY_CODES).contains(t.path("category_code").asString());
            assertThat(TRANSACTION_KINDS).contains(t.path("transaction_kind").asString());
            long amount = t.has("trans_amt") ? t.path("trans_amt").asLong() : t.path("approved_amt").asLong();
            assertThat(amount).isPositive(); // ck_financial_transactions_amount
        }
    }

    @Test
    void 납부_지연_임시값은_한_건이고_표시돼_있다() throws IOException {
        JsonNode bank = read("bank-shinhan.json");
        List<JsonNode> late = list(bank.path("account_list").get(0).path("trans_list")).stream()
                .filter(t -> t.path("_mock_note").asString("").startsWith("임시값"))
                .toList();

        assertThat(late).hasSize(1);
        assertThat(bank.path("_meta").path("temp_values")).hasSize(1);
        assertThat(bank.path("_meta").path("is_mock").asBoolean()).isTrue();
    }

    private static JsonNode read(String name) throws IOException {
        try (InputStream in = MockMydataFileTest.class.getResourceAsStream(DIR + name)) {
            assertThat(in).as(name).isNotNull();
            return MAPPER.readTree(in);
        }
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> result = new ArrayList<>();
        array.forEach(result::add);
        return result;
    }
}
