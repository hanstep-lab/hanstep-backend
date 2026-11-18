package com.hanstep.fx.service;

import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.fx.domain.FxComparator;
import com.hanstep.fx.domain.FxRateType;
import com.hanstep.fx.dto.FxAlertEvaluateResponse;
import com.hanstep.fx.dto.FxAlertEvaluateResponse.AlertResult;
import com.hanstep.fx.dto.FxAlertEvaluateResponse.CurrencyResult;
import com.hanstep.fx.repository.FxAlertRepository;
import com.hanstep.fx.repository.FxAlertRepository.ActiveAlert;
import com.hanstep.fx.repository.FxAlertRepository.JudgeRate;
import com.hanstep.fx.service.ExchangeRatesSavedEvent.SavedRate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 목표 환율 알림 도달 판정(FX-002, #125). 푸시는 없고 목록(GET /api/v1/fx/alerts)이 TRIGGERED·triggeredRate 를 보여 준다.
 * - 새 환율이 저장되면(ExchangeRatesSavedEvent: 1시간 수집·백필에서 새 행이거나 값이 바뀐 행) 그 통화의 ACTIVE 알림만 판정한다
 * - 비교 값: SEND 는 send_rate, BASE 는 base_rate. 그 행에 값이 없으면(송금 환율이 없는 MARKET_REF 의 SEND) 판정하지 않는다
 * - LTE 는 값 <= 목표, GTE 는 값 >= 목표면 TRIGGERED(triggered_rate_id, triggered_at). TRIGGERED 는 다시 보지 않는다
 * - Mock 행(is_mock)으로는 판정하지 않는다. 이미 지난 값으로 바로 판정하지 않게 알림 created_at 이후(같은 시각 포함) rate_at 만 본다
 * - 한 번에 여러 행이 오면(백필) rate_at 이 이른 행부터 보고 처음 닿은 행을 쓴다
 * - 동시성: 트랜잭션 없이 알림마다 조건부 UPDATE ... WHERE status = 'ACTIVE' 로 바꾼다(FxAlertRepository.trigger)
 */
@Service
public class FxAlertTriggerService {

    /** 알림 1건의 판정 결과. */
    public enum Result {
        /** 이번에 TRIGGERED 로 바꿈 */
        TRIGGERED,
        /** 목표에 닿지 않음 */
        NOT_REACHED,
        /** 그 행에 rateType 값이 없음(송금 환율이 없는 원천의 SEND) */
        NO_RATE_VALUE,
        /** 행의 rate_at 이 알림 created_at 보다 이름(이미 지난 값) */
        RATE_BEFORE_CREATED,
        /** 그 통화에 실데이터 환율이 없음 */
        NO_RATE,
        /** 닿았지만 그사이 다른 판정이나 삭제가 먼저 바꿈(UPDATE 0행) */
        ALREADY_CHANGED
    }

    record Judgement(long alertId, Result result, Long rateId, BigDecimal value) {
    }

    private static final Logger log = LoggerFactory.getLogger(FxAlertTriggerService.class);

    private final FxAlertRepository repository;

    public FxAlertTriggerService(FxAlertRepository repository) {
        this.repository = repository;
    }

    /** 수집기 저장 직후 같은 스레드에서 돈다. 오류는 수집기가 WARN 으로 남기고 저장에는 영향이 없다. */
    @EventListener
    public void onRatesSaved(ExchangeRatesSavedEvent event) {
        Map<String, List<Long>> idsByCurrency = new TreeMap<>();
        for (SavedRate rate : event.rates()) {
            idsByCurrency.computeIfAbsent(rate.currency(), c -> new ArrayList<>()).add(rate.rateId());
        }
        Instant now = now();
        idsByCurrency.forEach((currency, ids) -> {
            List<JudgeRate> rates = repository.realRates(ids);
            if (!rates.isEmpty()) {
                log(currency, event.backfill() ? "backfill" : "collect", judge(currency, rates, true, now));
            }
        });
    }

    /**
     * 내부 경로(시연·관리용): 현재 최신 실데이터 값으로 ACTIVE 알림을 다시 판정한다. currency 가 없으면 ACTIVE 알림이 있는 통화 전부.
     * ignoreCreatedAt 이면 created_at 규칙을 빼고 판정한다(시연에서 이미 있는 최신 값으로 도달 장면을 보여 줄 때).
     * currency 를 줬는데 실데이터가 없으면 404 FX_RATE_NOT_FOUND.
     */
    public FxAlertEvaluateResponse evaluateLatest(String currency, boolean ignoreCreatedAt) {
        List<String> currencies = currency == null ? repository.activeCurrencies() : List.of(currency);
        Instant now = now();
        List<CurrencyResult> results = new ArrayList<>();
        int triggered = 0;
        for (String c : currencies) {
            Optional<JudgeRate> latest = repository.latestRealRate(c);
            if (latest.isEmpty() && currency != null) {
                throw new BusinessException(ErrorCode.FX_RATE_NOT_FOUND);
            }
            List<Judgement> judged = latest.map(rate -> judge(c, List.of(rate), !ignoreCreatedAt, now))
                    .orElseGet(() -> repository.activeAlerts(c).stream()
                            .map(a -> new Judgement(a.id(), Result.NO_RATE, null, null)).toList());
            log(c, "evaluate", judged);
            triggered += (int) judged.stream().filter(j -> j.result() == Result.TRIGGERED).count();
            results.add(CurrencyResult.of(c, latest.orElse(null),
                    judged.stream().map(j -> AlertResult.of(j.alertId(), j.result().name(), j.value())).toList()));
        }
        return new FxAlertEvaluateResponse(ignoreCreatedAt, triggered, results);
    }

    List<Judgement> judge(String currency, List<JudgeRate> rates, boolean afterCreatedOnly, Instant now) {
        List<Judgement> judged = new ArrayList<>();
        for (ActiveAlert alert : repository.activeAlerts(currency)) {
            judged.add(judge(alert, rates, afterCreatedOnly, now));
        }
        return judged;
    }

    private Judgement judge(ActiveAlert alert, List<JudgeRate> rates, boolean afterCreatedOnly, Instant now) {
        boolean afterCreated = false;
        boolean hasValue = false;
        for (JudgeRate rate : rates) {
            if (afterCreatedOnly && rate.rateAt().isBefore(alert.createdAt())) {
                continue;
            }
            afterCreated = true;
            BigDecimal value = alert.rateType() == FxRateType.SEND ? rate.sendRate() : rate.baseRate();
            if (value == null) {
                continue;
            }
            hasValue = true;
            if (reached(value, alert.comparator(), alert.targetRate())) {
                Result result = repository.trigger(alert.id(), rate.id(), now) == 1
                        ? Result.TRIGGERED : Result.ALREADY_CHANGED;
                return new Judgement(alert.id(), result, rate.id(), value);
            }
        }
        Result result = !afterCreated ? Result.RATE_BEFORE_CREATED
                : !hasValue ? Result.NO_RATE_VALUE : Result.NOT_REACHED;
        return new Judgement(alert.id(), result, null, null);
    }

    /** 같은 값이면 닿은 것으로 본다(LTE 는 이하, GTE 는 이상). */
    static boolean reached(BigDecimal value, FxComparator comparator, BigDecimal target) {
        int compared = value.compareTo(target);
        return comparator == FxComparator.LTE ? compared <= 0 : compared >= 0;
    }

    private static void log(String currency, String trigger, List<Judgement> judged) {
        for (Judgement j : judged) {
            if (j.result() == Result.TRIGGERED) {
                log.info("fx alert triggered: alertId={} currency={} rateId={} value={} by={}", j.alertId(), currency,
                        j.rateId(), j.value().toPlainString(), trigger);
            }
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
