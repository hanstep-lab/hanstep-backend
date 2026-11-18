package com.hanstep.fx.service;

import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.fx.client.FrankfurterClient;
import com.hanstep.fx.client.FxFetch;
import com.hanstep.fx.client.FxRate;
import com.hanstep.fx.client.FxSourceException;
import com.hanstep.fx.client.KoreaeximClient;
import com.hanstep.fx.config.FxCollectorProperties;
import com.hanstep.fx.domain.FxSource;
import com.hanstep.fx.dto.FxBackfillResponse;
import com.hanstep.fx.dto.FxCollectResult;
import com.hanstep.fx.dto.FxCollectResult.CurrencyCount;
import com.hanstep.fx.dto.FxCollectResult.Failure;
import com.hanstep.fx.repository.ExchangeRateRepository;
import com.hanstep.fx.repository.ExchangeRateRepository.Outcome;
import com.hanstep.fx.repository.ExchangeRateRepository.Saved;
import com.hanstep.fx.service.ExchangeRatesSavedEvent.SavedRate;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 환율 수집(1시간 주기)과 백필(#119). 원천마다 따로 실행해 하나가 실패해도 나머지는 저장한다.
 * 실패는 WARN 한 줄(원천·통화·이유)로 남기고 던지지 않는다. 빈 응답(고시 전·비영업일)은 실패가 아니다.
 * 실데이터만 저장한다. 못 받은 통화·기간을 다른 값으로 채우지 않는다(화면·챗봇이 "데이터 부족"으로 처리).
 */
@Service
public class FxCollectService {

    // 11/17 결정: PHP 는 수출입은행 고시 통화가 아니라 Frankfurter(ECB 기준환율)로 받는다. 나머지는 수출입은행 고시 통화만
    static final Set<String> FRANKFURTER_CURRENCIES = Set.of("PHP");

    private static final Logger log = LoggerFactory.getLogger(FxCollectService.class);

    private final KoreaeximClient koreaexim;
    private final FrankfurterClient frankfurter;
    private final ExchangeRateRepository repository;
    private final ApplicationEventPublisher events;
    private final FxCollectorProperties properties;
    private final AtomicBoolean backfillRunning = new AtomicBoolean();

    public FxCollectService(KoreaeximClient koreaexim, FrankfurterClient frankfurter,
                            ExchangeRateRepository repository, ApplicationEventPublisher events,
                            FxCollectorProperties properties) {
        this.koreaexim = koreaexim;
        this.frankfurter = frankfurter;
        this.repository = repository;
        this.events = events;
        this.properties = properties;
    }

    /** 1시간 주기 수집 1번. 수출입은행은 오늘(KST) 고시, Frankfurter 는 최신 ECB 기준일 값. */
    public List<FxCollectResult> collectLatest(LocalDate today) {
        Run ecb = new Run(FxSource.MARKET_REF, FrankfurterClient.PROVIDER, frankfurterCurrencies(), false);
        for (String currency : ecb.currencies) {
            ecb.call(null, () -> frankfurter.latest(currency));
        }
        FxCollectResult ecbResult = ecb.finish();
        Run exim = new Run(FxSource.KOREAEXIM, null, koreaeximCurrencies(), false);
        if (!exim.currencies.isEmpty() && exim.checkApiKey(today)) {
            exim.call(today, () -> koreaexim.fetch(today, exim.currencies));
        }
        return List.of(ecbResult, exim.finish());
    }

    /**
     * from~to(포함)를 채운다. Frankfurter 는 통화마다 1번, 수출입은행은 평일마다 1번(주말은 부르지 않음) 부른다.
     * 수출입은행은 maxCalls 번에서 멈추고 nextFrom 을 돌려준다(나눠 실행). 인증키 오류·일일 한도면 바로 멈춘다.
     * 같은 값은 새 행을 만들지 않아 다시 실행해도 된다. 동시에 두 번은 실행하지 않는다(409 JOB_ALREADY_PROCESSING).
     */
    public FxBackfillResponse backfill(LocalDate from, LocalDate to, Set<FxSource> sources, int maxCalls) {
        if (!backfillRunning.compareAndSet(false, true)) {
            throw new BusinessException(ErrorCode.JOB_ALREADY_PROCESSING);
        }
        try {
            long started = System.nanoTime();
            List<FxCollectResult> results = new ArrayList<>();
            if (sources.contains(FxSource.MARKET_REF)) {
                Run ecb = new Run(FxSource.MARKET_REF, FrankfurterClient.PROVIDER, frankfurterCurrencies(), true);
                for (String currency : ecb.currencies) {
                    ecb.call(null, () -> frankfurter.range(currency, from, to));
                }
                results.add(ecb.finish());
            }
            if (sources.contains(FxSource.KOREAEXIM)) {
                results.add(koreaeximBackfill(from, to, maxCalls));
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            log.info("fx backfill {}~{} done in {} ms", from, to, elapsedMs);
            return new FxBackfillResponse(from, to, elapsedMs, results);
        } finally {
            backfillRunning.set(false);
        }
    }

    private FxCollectResult koreaeximBackfill(LocalDate from, LocalDate to, int maxCalls) {
        Run exim = new Run(FxSource.KOREAEXIM, null, koreaeximCurrencies(), true);
        if (exim.currencies.isEmpty() || !exim.checkApiKey(from)) {
            return exim.finish();
        }
        long intervalMs = properties.backfill().callInterval().toMillis();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            if (exim.calls >= maxCalls) {
                exim.nextFrom = date;
                break;
            }
            if (exim.calls > 0 && intervalMs > 0 && !sleep(intervalMs)) {
                exim.nextFrom = date;
                break;
            }
            LocalDate searchDate = date;
            if (!exim.call(searchDate, () -> koreaexim.fetch(searchDate, exim.currencies))) {
                exim.nextFrom = date;
                break;
            }
        }
        return exim.finish();
    }

    private List<String> frankfurterCurrencies() {
        return properties.currencyList().stream().filter(FRANKFURTER_CURRENCIES::contains).toList();
    }

    private List<String> koreaeximCurrencies() {
        return properties.currencyList().stream().filter(c -> !FRANKFURTER_CURRENCIES.contains(c)).toList();
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 원천 1번 실행(수집 1번 또는 백필 1번)의 집계. */
    private final class Run {

        private final FxSource source;
        private final String provider;
        private final List<String> currencies;
        private final boolean backfill;
        private final Map<String, Tally> tallies = new LinkedHashMap<>();
        private final List<Failure> failures = new ArrayList<>();
        private final List<SavedRate> saved = new ArrayList<>();
        private int calls;
        private int succeededCalls;
        private int emptyResponses;
        private LocalDate nextFrom;

        Run(FxSource source, String provider, List<String> currencies, boolean backfill) {
            this.source = source;
            this.provider = provider;
            this.currencies = currencies;
            this.backfill = backfill;
            currencies.forEach(c -> tallies.put(c, new Tally()));
        }

        /** 인증키가 없으면 부르지 않고 실패로 남긴다. */
        boolean checkApiKey(LocalDate date) {
            if (properties.koreaexim().hasApiKey()) {
                return true;
            }
            fail(date, "KOREAEXIM_API_KEY is not set");
            nextFrom = backfill ? date : null;
            return false;
        }

        /** 1번 부르고 저장한다. 더 부르면 안 되는 실패(인증키 오류·일일 한도·DB 오류 등)면 false. */
        boolean call(LocalDate date, Supplier<FxFetch> fetch) {
            calls++;
            FxFetch result;
            try {
                result = fetch.get();
            } catch (FxSourceException e) {
                fail(date, e.reason());
                return !e.isFatal();
            } catch (RuntimeException e) {
                fail(date, "unexpected " + e.getClass().getSimpleName() + ": " + FxSourceException.mask(e.getMessage()));
                return false;
            }
            succeededCalls++;
            if (result.rates().isEmpty() && result.rejections().isEmpty()) {
                emptyResponses++;
                return true;
            }
            try {
                save(date, result);
            } catch (RuntimeException e) {
                fail(date, "save failed " + e.getClass().getSimpleName());
                return false;
            }
            return true;
        }

        private void save(LocalDate date, FxFetch result) {
            Instant fetchedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            for (FxRate rate : result.rates()) {
                Saved s = repository.upsert(source, provider, rate, fetchedAt);
                Tally tally = tallies.computeIfAbsent(rate.currency(), c -> new Tally());
                switch (s.outcome()) {
                    case INSERTED -> tally.inserted++;
                    case UPDATED -> tally.updated++;
                    case UNCHANGED -> tally.unchanged++;
                    case KEPT_MOCK -> tally.keptMock++;
                }
                if (s.outcome() == Outcome.INSERTED || s.outcome() == Outcome.UPDATED) {
                    saved.add(new SavedRate(s.id(), rate.currency(), rate.rateDate(), rate.rateAt()));
                }
            }
            for (FxFetch.Rejection rejection : result.rejections()) {
                Tally tally = tallies.computeIfAbsent(rejection.currency(), c -> new Tally());
                if (tally.rejected++ == 0) {
                    tally.firstRejection = (date == null ? "" : date + " ") + rejection.reason();
                }
            }
        }

        private void fail(LocalDate date, String reason) {
            failures.add(new Failure(date, reason));
            log.warn("fx collect failed: source={} currencies={} date={} reason={}", source, currencies, date, reason);
        }

        FxCollectResult finish() {
            tallies.forEach((currency, t) -> {
                if (t.rejected > 0) {
                    log.warn("fx rate rejected: source={} currency={} count={} first={}", source, currency, t.rejected,
                            t.firstRejection);
                }
                if (t.keptMock > 0) {
                    log.warn("fx rate kept mock rows: source={} currency={} count={} (run the mock cleanup SQL first)",
                            source, currency, t.keptMock);
                }
            });
            if (!saved.isEmpty()) {
                log.info("fx collect saved: source={} backfill={} rows={}", source, backfill, saved.size());
                publish();
            }
            return new FxCollectResult(source, provider, status(), calls, emptyResponses,
                    tallies.entrySet().stream().map(e -> e.getValue().count(e.getKey())).toList(),
                    List.copyOf(failures), nextFrom);
        }

        private String status() {
            if (currencies.isEmpty()) {
                return "SKIPPED";
            }
            if (!failures.isEmpty() && succeededCalls == 0) {
                return "FAILED";
            }
            return failures.isEmpty() && nextFrom == null ? "OK" : "PARTIAL";
        }

        private void publish() {
            try {
                events.publishEvent(new ExchangeRatesSavedEvent(source, backfill, List.copyOf(saved)));
            } catch (RuntimeException e) {
                // 저장은 이미 끝났다. 리스너 오류로 수집을 멈추지 않는다
                log.warn("fx rates saved event listener failed: source={} reason={}", source, e.toString());
            }
        }
    }

    private static final class Tally {
        private int inserted;
        private int updated;
        private int unchanged;
        private int keptMock;
        private int rejected;
        private String firstRejection;

        CurrencyCount count(String currency) {
            return new CurrencyCount(currency, inserted, updated, unchanged, keptMock, rejected);
        }
    }
}
