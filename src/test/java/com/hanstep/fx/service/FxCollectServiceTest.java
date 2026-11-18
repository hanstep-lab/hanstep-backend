package com.hanstep.fx.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hanstep.common.error.BusinessException;
import com.hanstep.fx.FakeFxSourceServer;
import com.hanstep.fx.domain.FxSource;
import com.hanstep.fx.dto.FxBackfillResponse;
import com.hanstep.fx.dto.FxCollectResult;
import com.hanstep.fx.dto.FxCollectResult.CurrencyCount;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수집·백필 저장(실제 DB, 테스트마다 롤백). 원천은 FakeFxSourceServer. 날짜는 2031년이라 로컬 DB 의 다른 환율과 키가 겹치지 않는다.
 * 2031-01-06 은 월요일.
 */
@SpringBootTest
@Transactional
@RecordApplicationEvents
@ExtendWith(OutputCaptureExtension.class)
class FxCollectServiceTest {

    private static final LocalDate MONDAY = LocalDate.of(2031, 1, 6);

    @Autowired
    private FxCollectService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEvents events;

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) {
        FakeFxSourceServer.register(registry);
    }

    @BeforeEach
    void setUp() {
        FakeFxSourceServer.reset();
    }

    // ---- 1시간 주기 수집 ----

    @Test
    void 수집한_값을_원천별로_저장하고_Mock_표시는_하지_않는다() {
        List<FxCollectResult> results = service.collectLatest(MONDAY);

        assertThat(results).extracting(FxCollectResult::status).containsExactly("OK", "OK");
        assertThat(count(results, FxSource.KOREAEXIM, "USD").inserted()).isEqualTo(1);
        assertThat(count(results, FxSource.MARKET_REF, "PHP").inserted()).isEqualTo(1);
        assertThat(FakeFxSourceServer.KOREAEXIM_QUERIES).containsExactly(
                "authkey=" + FakeFxSourceServer.KOREAEXIM_KEY + "&searchdate=20310106&data=AP01");
        assertThat(FakeFxSourceServer.FRANKFURTER_QUERIES).containsExactly("base=PHP&quotes=KRW");

        Map<String, Object> usd = jdbc.queryForMap("SELECT * FROM exchange_rates WHERE currency = 'USD' "
                + "AND source = 'KOREAEXIM' AND rate_date = '2031-01-06'");
        assertThat(usd.get("rate_at").toString()).isEqualTo("2031-01-05T15:00");
        assertThat(usd.get("base_rate").toString()).isEqualTo("1306.4000");
        assertThat(usd.get("send_rate").toString()).isEqualTo("1306.7200");
        assertThat(usd.get("receive_rate").toString()).isEqualTo("1306.0700");
        assertThat(usd.get("provider")).isNull();
        assertThat(usd.get("is_mock")).isEqualTo(false);

        Map<String, Object> php = jdbc.queryForMap("SELECT * FROM exchange_rates WHERE currency = 'PHP' "
                + "AND source = 'MARKET_REF' AND rate_date = '2031-01-03'");
        assertThat(php.get("rate_at").toString()).isEqualTo("2031-01-03T15:00");
        assertThat(php.get("base_rate").toString()).isEqualTo("20.0030");
        assertThat(php.get("send_rate")).isNull();
        assertThat(php.get("provider")).isEqualTo("frankfurter-ecb");
        assertThat(php.get("is_mock")).isEqualTo(false);
    }

    @Test
    void 일시간_수집에서_같은_값을_다시_받으면_새_행_없이_두_통화의_fetched_at만_고친다() throws Exception {
        service.collectLatest(MONDAY);
        Map<String, String> before = snapshot2031(false);
        Map<String, Object> firstSeen = fetchedAtByCurrency();
        Thread.sleep(5);

        List<FxCollectResult> again = service.collectLatest(MONDAY);

        assertThat(count(again, FxSource.KOREAEXIM, "USD")).isEqualTo(new CurrencyCount("USD", 0, 0, 1, 0, 0));
        assertThat(count(again, FxSource.MARKET_REF, "PHP")).isEqualTo(new CurrencyCount("PHP", 0, 0, 1, 0, 0));
        assertThat(rows2031()).isEqualTo(2);
        // 값·키는 그대로이고 fetched_at(마지막 확인 시각)만 늦어진다. USD·PHP 둘 다
        assertThat(snapshot2031(false)).isEqualTo(before);
        Map<String, Object> touched = fetchedAtByCurrency();
        assertThat((LocalDateTime) touched.get("USD")).isAfter((LocalDateTime) firstSeen.get("USD"));
        assertThat((LocalDateTime) touched.get("PHP")).isAfter((LocalDateTime) firstSeen.get("PHP"));
        // 저장 이벤트는 새 행·값이 바뀐 행에만 낸다
        assertThat(events.stream(ExchangeRatesSavedEvent.class)).hasSize(2);
    }

    @Test
    void 값이_바뀌면_같은_행을_고친다() {
        service.collectLatest(MONDAY);
        FakeFxSourceServer.koreaexim = d -> """
                [{"result":1,"cur_unit":"USD","ttb":"1,400.07","tts":"1,430.72","deal_bas_r":"1,415.4"}]""";

        List<FxCollectResult> results = service.collectLatest(MONDAY);

        assertThat(count(results, FxSource.KOREAEXIM, "USD").updated()).isEqualTo(1);
        assertThat(rows2031()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT base_rate FROM exchange_rates WHERE currency = 'USD' "
                + "AND rate_date = '2031-01-06'", String.class)).isEqualTo("1415.4000");
    }

    @Test
    void 수출입은행이_실패해도_PHP는_저장하고_WARN_한_줄만_남긴다(CapturedOutput output) {
        FakeFxSourceServer.koreaeximStatus = 500;

        List<FxCollectResult> results = service.collectLatest(MONDAY);

        assertThat(results).extracting(FxCollectResult::status).containsExactly("OK", "FAILED");
        assertThat(count(results, FxSource.MARKET_REF, "PHP").inserted()).isEqualTo(1);
        assertThat(results.get(1).failures()).singleElement()
                .satisfies(f -> assertThat(f.reason()).isEqualTo("HTTP 500"));
        assertThat(output.getOut()).containsOnlyOnce("fx collect failed: source=KOREAEXIM currencies=[USD] "
                + "date=2031-01-06 reason=HTTP 500");
        assertThat(output.getOut()).doesNotContain(FakeFxSourceServer.KOREAEXIM_KEY);
    }

    @Test
    void Frankfurter가_실패해도_USD는_저장한다(CapturedOutput output) {
        FakeFxSourceServer.frankfurterStatus = 503;

        List<FxCollectResult> results = service.collectLatest(MONDAY);

        assertThat(results).extracting(FxCollectResult::status).containsExactly("FAILED", "OK");
        assertThat(count(results, FxSource.KOREAEXIM, "USD").inserted()).isEqualTo(1);
        assertThat(output.getOut()).contains("fx collect failed: source=MARKET_REF currencies=[PHP] date=null "
                + "reason=HTTP 503");
    }

    @Test
    void 인증키_오류도_키를_로그에_남기지_않는다(CapturedOutput output) {
        FakeFxSourceServer.koreaexim = d -> "[{\"result\":3,\"cur_unit\":null}]";

        List<FxCollectResult> results = service.collectLatest(MONDAY);

        assertThat(results.get(1).failures().getFirst().reason()).isEqualTo("result=3 (invalid auth key)");
        assertThat(output.getOut()).contains("reason=result=3 (invalid auth key)")
                .doesNotContain(FakeFxSourceServer.KOREAEXIM_KEY);
    }

    @Test
    void 고시_전_빈_응답은_실패가_아니다(CapturedOutput output) {
        FakeFxSourceServer.koreaexim = d -> "[]";

        List<FxCollectResult> results = service.collectLatest(MONDAY);

        assertThat(results.get(1).status()).isEqualTo("OK");
        assertThat(results.get(1).emptyResponses()).isEqualTo(1);
        assertThat(results.get(1).failures()).isEmpty();
        assertThat(output.getOut()).doesNotContain("fx collect failed");
    }

    @Test
    void 같은_키에_Mock_행이_있으면_건드리지_않는다() {
        jdbc.update("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, send_rate, "
                + "receive_rate, source, is_mock, fetched_at) VALUES ('USD', 1, '2031-01-06', '2031-01-05 15:00:00', "
                + "999.0000, 1009.0000, 989.0000, 'KOREAEXIM', TRUE, '2031-01-06 02:05:00')");

        List<FxCollectResult> results = service.collectLatest(MONDAY);

        assertThat(count(results, FxSource.KOREAEXIM, "USD")).isEqualTo(new CurrencyCount("USD", 0, 0, 0, 1, 0));
        // Mock 행은 값도 fetched_at 도 고치지 않는다
        assertThat(jdbc.queryForObject("SELECT CONCAT(base_rate, ':', is_mock, ':', fetched_at) FROM exchange_rates "
                + "WHERE currency = 'USD' AND rate_date = '2031-01-06'", String.class))
                .isEqualTo("999.0000:1:2031-01-06 02:05:00.000");
    }

    @Test
    void 새로_저장하면_이벤트를_내고_같은_값이면_내지_않는다() {
        service.collectLatest(MONDAY);

        List<ExchangeRatesSavedEvent> saved = events.stream(ExchangeRatesSavedEvent.class).toList();
        assertThat(saved).extracting(ExchangeRatesSavedEvent::source)
                .containsExactly(FxSource.MARKET_REF, FxSource.KOREAEXIM);
        assertThat(saved).allMatch(e -> !e.backfill() && e.rates().size() == 1);
        long usdId = jdbc.queryForObject("SELECT id FROM exchange_rates WHERE currency = 'USD' "
                + "AND rate_date = '2031-01-06'", Long.class);
        assertThat(saved.get(1).rates().getFirst().rateId()).isEqualTo(usdId);

        events.clear();
        service.collectLatest(MONDAY);
        assertThat(events.stream(ExchangeRatesSavedEvent.class)).isEmpty();
    }

    @Test
    void 수집은_기본으로_꺼져_있다() {
        assertThat(context.getBeansOfType(FxCollectScheduler.class)).isEmpty();
    }

    // ---- 백필 ----

    @Test
    void 백필은_평일만_부르고_다시_해도_새_행도_fetched_at_변경도_없다() throws Exception {
        LocalDate from = MONDAY;
        LocalDate to = MONDAY.plusDays(13); // 2주(평일 10일)

        FxBackfillResponse first = service.backfill(from, to, EnumSet.allOf(FxSource.class), 400);

        FxCollectResult ecb = first.sources().get(0);
        FxCollectResult exim = first.sources().get(1);
        assertThat(ecb.calls()).isEqualTo(1);
        assertThat(FakeFxSourceServer.FRANKFURTER_QUERIES)
                .containsExactly("base=PHP&quotes=KRW&from=2031-01-06&to=2031-01-19");
        assertThat(exim.calls()).isEqualTo(10);
        assertThat(FakeFxSourceServer.KOREAEXIM_QUERIES).hasSize(10).noneMatch(q -> q.contains("searchdate=20310111"));
        assertThat(count(List.of(ecb), FxSource.MARKET_REF, "PHP").inserted()).isEqualTo(10);
        assertThat(count(List.of(exim), FxSource.KOREAEXIM, "USD").inserted()).isEqualTo(10);
        assertThat(rows2031()).isEqualTo(20);
        assertThat(events.stream(ExchangeRatesSavedEvent.class)).allMatch(ExchangeRatesSavedEvent::backfill);

        Map<String, String> before = snapshot2031(true);
        Thread.sleep(5);
        FxBackfillResponse second = service.backfill(from, to, EnumSet.allOf(FxSource.class), 400);

        assertThat(second.sources()).flatExtracting(FxCollectResult::currencies).containsExactly(
                new CurrencyCount("PHP", 0, 0, 10, 0, 0), new CurrencyCount("USD", 0, 0, 10, 0, 0));
        assertThat(rows2031()).isEqualTo(20);
        // 백필 재실행은 아무것도 쓰지 않는다: 새 행 0, 값·fetched_at 변경 0
        assertThat(snapshot2031(true)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchange_rates WHERE rate_date BETWEEN '2031-01-01' "
                + "AND '2031-12-31' AND (source = 'MOCK' OR is_mock)", Integer.class)).isZero();
    }

    @Test
    void 백필은_호출_상한에서_멈추고_이어서_할_날짜를_준다() {
        FxBackfillResponse response = service.backfill(MONDAY, MONDAY.plusDays(13), EnumSet.of(FxSource.KOREAEXIM), 3);

        FxCollectResult exim = response.sources().getFirst();
        assertThat(exim.calls()).isEqualTo(3);
        assertThat(exim.status()).isEqualTo("PARTIAL");
        assertThat(exim.nextFrom()).isEqualTo(LocalDate.of(2031, 1, 9));

        FxBackfillResponse rest = service.backfill(exim.nextFrom(), MONDAY.plusDays(13),
                EnumSet.of(FxSource.KOREAEXIM), 400);
        assertThat(rest.sources().getFirst().calls()).isEqualTo(7);
        assertThat(rows2031()).isEqualTo(10);
    }

    @Test
    void 백필은_인증키_오류면_바로_멈춘다() {
        FakeFxSourceServer.koreaexim = d -> "[{\"result\":3}]";

        FxCollectResult exim = service.backfill(MONDAY, MONDAY.plusDays(13), EnumSet.of(FxSource.KOREAEXIM), 400)
                .sources().getFirst();

        assertThat(exim.calls()).isEqualTo(1);
        assertThat(exim.status()).isEqualTo("FAILED");
        assertThat(exim.nextFrom()).isEqualTo(MONDAY);
    }

    @Test
    void 백필_중_하루가_실패해도_나머지_날짜는_저장한다() {
        FakeFxSourceServer.koreaexim = d -> d.equals(MONDAY.plusDays(1)) ? "<html>" : FakeFxSourceServer.usd(d);

        FxCollectResult exim = service.backfill(MONDAY, MONDAY.plusDays(4), EnumSet.of(FxSource.KOREAEXIM), 400)
                .sources().getFirst();

        assertThat(exim.status()).isEqualTo("PARTIAL");
        assertThat(exim.failures()).singleElement().satisfies(f -> assertThat(f.date()).isEqualTo(MONDAY.plusDays(1)));
        assertThat(exim.currencies().getFirst().inserted()).isEqualTo(4);
        assertThat(exim.nextFrom()).isNull();
    }

    @Test
    void 백필은_동시에_두_번_돌지_않는다() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeFxSourceServer.frankfurter = q -> {
            inside.countDown();
            await(release);
            return "[]";
        };
        Thread first = new Thread(() -> service.backfill(MONDAY, MONDAY, EnumSet.of(FxSource.MARKET_REF), 1));
        first.start();
        try {
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.backfill(MONDAY, MONDAY, EnumSet.of(FxSource.MARKET_REF), 1))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode().name()).isEqualTo("JOB_ALREADY_PROCESSING"));
        } finally {
            release.countDown();
            first.join(10_000);
        }
    }

    /** 2031년 행 전부의 id → 값(withFetchedAt 이면 fetched_at 도). 그 칸이 바뀌면 달라진다. */
    private Map<String, String> snapshot2031(boolean withFetchedAt) {
        Map<String, String> rows = new TreeMap<>();
        jdbc.query("SELECT id, currency, source, rate_at, base_rate, send_rate, receive_rate, fetched_at "
                        + "FROM exchange_rates WHERE rate_date BETWEEN '2031-01-01' AND '2031-12-31'",
                rs -> {
                    rows.put(rs.getString("id"), rs.getString("currency") + "|" + rs.getString("source") + "|"
                            + rs.getString("rate_at") + "|" + rs.getString("base_rate") + "|" + rs.getString("send_rate")
                            + "|" + rs.getString("receive_rate") + (withFetchedAt ? "|" + rs.getString("fetched_at") : ""));
                });
        return rows;
    }

    private Map<String, Object> fetchedAtByCurrency() {
        Map<String, Object> result = new HashMap<>();
        jdbc.query("SELECT currency, fetched_at FROM exchange_rates WHERE rate_date BETWEEN '2031-01-01' AND "
                + "'2031-12-31'", rs -> {
                    result.put(rs.getString("currency"), rs.getObject("fetched_at", LocalDateTime.class));
                });
        return result;
    }

    private int rows2031() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM exchange_rates WHERE rate_date BETWEEN '2031-01-01' "
                + "AND '2031-12-31'", Integer.class);
    }

    private static CurrencyCount count(List<FxCollectResult> results, FxSource source, String currency) {
        return results.stream().filter(r -> r.source() == source).flatMap(r -> r.currencies().stream())
                .filter(c -> c.currency().equals(currency)).findFirst().orElseThrow();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
