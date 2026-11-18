package com.hanstep.fx.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.support.TestUsers;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * 환율 화면 API(FX-001·FX-002, #123). 실제 DB, 테스트마다 롤백.
 * 로컬 DB 의 USD·PHP 데이터와 섞이지 않게 시험용 국적 XTL → XTS(ISO 4217 시험용 코드), XXL → XXX 를 매핑하고
 * 그 통화로 계산을 본다. 기준일은 2099년이라 다른 데이터와 키가 겹치지 않는다. Mock 은 읽지 않는다(기본 설정).
 */
@SpringBootTest(properties = {
        "hanstep.fx-rates.home-currencies[0].nationality=PHL", "hanstep.fx-rates.home-currencies[0].currency=PHP",
        "hanstep.fx-rates.home-currencies[1].nationality=VNM", "hanstep.fx-rates.home-currencies[1].currency=VND",
        "hanstep.fx-rates.home-currencies[2].nationality=XTL", "hanstep.fx-rates.home-currencies[2].currency=XTS",
        "hanstep.fx-rates.home-currencies[3].nationality=XXL", "hanstep.fx-rates.home-currencies[3].currency=XXX"})
@AutoConfigureMockMvc
@Transactional
class FxRateApiTest {

    private static final String URL = "/api/v1/fx/rates";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private long userId;
    private String bearer;

    @BeforeEach
    void setUp() {
        userId = TestUsers.create(jdbc, "test-fx-rates", "TEST FX RATES");
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
        nationality(userId, "XTL");
    }

    /**
     * XTS 수출입은행 형식(송금 환율 있음). 2098-01-02 부터 있어 1Y 까지 평균을 낸다.
     * 2099-03-31 기준 1M 시작은 2099-02-28(포함): 02-27 은 빠지고 02-28·03-15·03-31 만 들어간다.
     */
    private void koreaeximSeries() {
        rate("XTS", "KOREAEXIM", false, "2098-01-02", "2098-01-01 15:00:00", "500.0000", "505.0000");
        rate("XTS", "KOREAEXIM", false, "2099-02-27", "2099-02-26 15:00:00", "900.0000", "909.0000");
        rate("XTS", "KOREAEXIM", false, "2099-02-28", "2099-02-27 15:00:00", "1000.0000", "1010.0000");
        rate("XTS", "KOREAEXIM", false, "2099-03-15", "2099-03-14 15:00:00", "1100.0000", "1111.0000");
        rate("XTS", "KOREAEXIM", false, "2099-03-31", "2099-03-30 15:00:00", "1300.0000", "1313.0000");
    }

    @Test
    void 현재값_전_영업일_대비_기간_평균을_준다() throws Exception {
        koreaeximSeries();

        rate("XTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("XTS"))
                .andExpect(jsonPath("$.unit").value(1))
                .andExpect(jsonPath("$.rate").value(1300))
                .andExpect(jsonPath("$.sendRate").value(1313))
                .andExpect(jsonPath("$.receiveRate").value(1287))
                // 전 영업일(03-15) 1100 대비 +200, 200 ÷ 1100 = 18.1818% → 18.18
                .andExpect(jsonPath("$.change").value(200))
                .andExpect(jsonPath("$.changeRate").value(18.18))
                .andExpect(jsonPath("$.asOf").value("2099-03-31"))
                .andExpect(jsonPath("$.source").value("KOREAEXIM"))
                .andExpect(jsonPath("$.sourceLabel").value("수출입은행 고시 매매기준율"))
                .andExpect(jsonPath("$.isMock").value(false))
                .andExpect(jsonPath("$.rateAt").value("2099-03-31T00:00:00+09:00"))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.notices.length()").value(0))
                .andExpect(jsonPath("$.screenCurrencies[0]").value("USD"))
                .andExpect(jsonPath("$.screenCurrencies[1]").value("XTS"))
                // 1M: 02-28(포함)·03-15·03-31 = (1000 + 1100 + 1300) ÷ 3 = 1133.3333(02-27 은 빠짐)
                .andExpect(jsonPath("$.periodAverages[0].period").value("1M"))
                .andExpect(jsonPath("$.periodAverages[0].avg").value(1133.3333))
                .andExpect(jsonPath("$.periodAverages[0].diff").value(166.6667))
                .andExpect(jsonPath("$.periodAverages[0].diffRate").value(14.71))
                .andExpect(jsonPath("$.periodAverages[0].direction").value("HIGHER"))
                .andExpect(jsonPath("$.periodAverages[0].businessDays").value(3))
                .andExpect(jsonPath("$.periodAverages[0].reason").isEmpty())
                // 1Y: 2098-03-31 이후 4일(2098-01-02 는 빠짐) = 1075
                .andExpect(jsonPath("$.periodAverages[3].period").value("1Y"))
                .andExpect(jsonPath("$.periodAverages[3].avg").value(1075))
                .andExpect(jsonPath("$.periodAverages[3].businessDays").value(4));
    }

    @Test
    void 영업일별로_그날의_마지막_rate_at_값을_쓴다() throws Exception {
        koreaeximSeries();
        // 같은 기준일에 시각이 더 늦은 값이 있으면 그 값이 그날 값이다
        rate("XTS", "KOREAEXIM", false, "2099-03-31", "2099-03-31 02:00:00", "1400.0000", "1414.0000");

        rate("XTS")
                .andExpect(jsonPath("$.rate").value(1400))
                .andExpect(jsonPath("$.periodAverages[0].avg").value(1166.6667))
                .andExpect(jsonPath("$.periodAverages[0].businessDays").value(3));
        history("XTS", "1M")
                .andExpect(jsonPath("$.points.length()").value(3))
                .andExpect(jsonPath("$.points[2].rate").value(1400));
    }

    @Test
    void 데이터가_기간보다_짧으면_그_기간은_null과_INSUFFICIENT_DATA() throws Exception {
        rate("XTS", "KOREAEXIM", false, "2099-02-28", "2099-02-27 15:00:00", "1000.0000", "1010.0000");
        rate("XTS", "KOREAEXIM", false, "2099-03-31", "2099-03-30 15:00:00", "1300.0000", "1313.0000");

        rate("XTS")
                .andExpect(jsonPath("$.periodAverages[0].avg").value(1150))
                .andExpect(jsonPath("$.periodAverages[1].period").value("3M"))
                .andExpect(jsonPath("$.periodAverages[1].avg").isEmpty())
                .andExpect(jsonPath("$.periodAverages[1].diffRate").isEmpty())
                .andExpect(jsonPath("$.periodAverages[1].direction").isEmpty())
                .andExpect(jsonPath("$.periodAverages[1].reason").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.periodAverages[1].businessDays").value(2))
                .andExpect(jsonPath("$.periodAverages[3].reason").value("INSUFFICIENT_DATA"));
        history("XTS", "3M")
                .andExpect(jsonPath("$.avg").isEmpty())
                .andExpect(jsonPath("$.reason").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.min").value(1000))
                .andExpect(jsonPath("$.points.length()").value(2));
    }

    @Test
    void 원천이_섞이면_우선_원천_KOREAEXIM_값만_쓴다() throws Exception {
        koreaeximSeries();
        // 더 늦은 MARKET_REF 값이 있어도 KOREAEXIM 이 우선이고, 평균에 섞지 않는다
        marketRef("XTS", "2099-04-01", "2099-04-01 14:00:00", "9999.0000", nowUtc());
        marketRef("XTS", "2099-03-15", "2099-03-15 15:00:00", "9999.0000", nowUtc());

        rate("XTS")
                .andExpect(jsonPath("$.source").value("KOREAEXIM"))
                .andExpect(jsonPath("$.asOf").value("2099-03-31"))
                .andExpect(jsonPath("$.periodAverages[0].avg").value(1133.3333));
        history("XTS", "1M").andExpect(jsonPath("$.max").value(1300));
    }

    @Test
    void 참고_환율만_있는_통화는_송금_환율이_null이고_REFERENCE_RATE_ONLY() throws Exception {
        nationality(userId, "XXL");
        marketRef("XXX", "2099-03-31", "2099-03-31 14:00:00", "20.9840", nowUtc());

        rate("XXX")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("MARKET_REF"))
                .andExpect(jsonPath("$.sourceLabel").value("ECB 기준환율 기반 참고값"))
                .andExpect(jsonPath("$.sendRate").isEmpty())
                .andExpect(jsonPath("$.receiveRate").isEmpty())
                .andExpect(jsonPath("$.notices[0]").value("REFERENCE_RATE_ONLY"))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.change").isEmpty())
                .andExpect(jsonPath("$.rateAt").value("2099-03-31T23:00:00+09:00"));
    }

    @Test
    void 참고_환율은_마지막_확인_시각이_3시간_넘게_지나면_stale() throws Exception {
        nationality(userId, "XXL");
        marketRef("XXX", "2099-03-31", "2099-03-31 14:00:00", "20.9840", "2026-01-01 00:00:00");

        rate("XXX").andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.fetchedAt").value("2026-01-01T09:00:00+09:00"));
    }

    @Test
    void Mock_환율은_읽지_않는다() throws Exception {
        // USD Mock 행처럼 source 가 KOREAEXIM 이어도 is_mock 이면 쓰지 않는다
        rate("XTS", "KOREAEXIM", true, "2099-03-31", "2099-03-30 15:00:00", "1300.0000", "1313.0000");
        rate("XTS", "MOCK", true, "2099-04-01", "2099-03-31 15:00:00", "1301.0000", "1314.0000");

        rate("XTS").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FX_RATE_NOT_FOUND"));
    }

    @Test
    void 추이는_영업일별_값과_최저_평균_최고_현재를_준다() throws Exception {
        koreaeximSeries();

        history("XTS", "1M")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("XTS"))
                .andExpect(jsonPath("$.period").value("1M"))
                .andExpect(jsonPath("$.points.length()").value(3))
                .andExpect(jsonPath("$.points[0].date").value("2099-02-28"))
                .andExpect(jsonPath("$.points[0].rate").value(1000))
                .andExpect(jsonPath("$.points[2].date").value("2099-03-31"))
                .andExpect(jsonPath("$.min").value(1000))
                .andExpect(jsonPath("$.max").value(1300))
                .andExpect(jsonPath("$.avg").value(1133.3333))
                .andExpect(jsonPath("$.current").value(1300))
                .andExpect(jsonPath("$.businessDays").value(3))
                .andExpect(jsonPath("$.reason").isEmpty())
                .andExpect(jsonPath("$.source").value("KOREAEXIM"))
                .andExpect(jsonPath("$.isMock").value(false))
                .andExpect(jsonPath("$.asOf").value("2099-03-31"));
        // 12M 은 1Y 로 받는다
        history("XTS", "12M").andExpect(jsonPath("$.period").value("1Y")).andExpect(jsonPath("$.avg").value(1075));
        history("XTS", "2W")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("period"));
    }

    @Test
    void 화면_통화는_USD와_국적의_모국_통화() throws Exception {
        rate("USD", "KOREAEXIM", false, "2099-03-31", "2099-03-30 15:00:00", "1338.8000", "1352.1800");

        nationality(userId, "PHL");
        rate("USD").andExpect(jsonPath("$.screenCurrencies").value(Matchers.contains("USD", "PHP")));
        nationality(userId, "VNM");
        rate("USD").andExpect(jsonPath("$.screenCurrencies").value(Matchers.contains("USD", "VND")));
        jdbc.update("DELETE FROM visa_profiles WHERE user_id = ?", userId);
        rate("USD").andExpect(jsonPath("$.screenCurrencies").value(Matchers.contains("USD")));
        // 모국 통화가 없으면 PHP 는 화면 통화가 아니다
        rate("PHP").andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.fields[0].field").value("currency"));
        // currency 를 빼면 모국 통화(없으면 USD)
        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void 화면_통화가_아니면_400_데이터가_없으면_404_토큰이_없으면_401() throws Exception {
        rate("JPY").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        history("JPY", "1M").andExpect(status().isBadRequest());
        rate("XTS").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FX_RATE_NOT_FOUND"));
        history("XTS", "1M").andExpect(status().isNotFound());
        mockMvc.perform(get(URL).param("currency", "XTS")).andExpect(status().isUnauthorized());
    }

    private ResultActions rate(String currency) throws Exception {
        return mockMvc.perform(get(URL).param("currency", currency).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private ResultActions history(String currency, String period) throws Exception {
        return mockMvc.perform(get(URL + "/" + currency + "/history").param("period", period)
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private void nationality(long user, String code) {
        jdbc.update("INSERT INTO visa_profiles (user_id, nationality_code) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE nationality_code = VALUES(nationality_code)", user, code);
    }

    /** 수출입은행 형식 1행(받을 때 = 매매기준율 × 0.99). 시각은 UTC. */
    private void rate(String currency, String source, boolean mock, String rateDate, String rateAtUtc, String base,
                      String send) {
        String receive = new BigDecimal(base).multiply(new BigDecimal("0.99")).toPlainString();
        jdbc.update("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, send_rate, "
                        + "receive_rate, source, is_mock, fetched_at) VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?)",
                currency, rateDate, rateAtUtc, base, send, receive, source, mock, rateAtUtc);
    }

    private void marketRef(String currency, String rateDate, String rateAtUtc, String base, String fetchedAtUtc) {
        jdbc.update("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, source, provider, "
                        + "is_mock, fetched_at) VALUES (?, 1, ?, ?, ?, 'MARKET_REF', 'frankfurter-ecb', FALSE, ?)",
                currency, rateDate, rateAtUtc, base, fetchedAtUtc);
    }

    private static String nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
