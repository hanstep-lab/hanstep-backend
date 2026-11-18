package com.hanstep.fx.service;

import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.fx.domain.FxRateType;
import com.hanstep.fx.dto.FxAlertCreateRequest;
import com.hanstep.fx.dto.FxAlertListResponse;
import com.hanstep.fx.dto.FxAlertResponse;
import com.hanstep.fx.repository.FxAlertRepository;
import com.hanstep.fx.repository.FxAlertRepository.CurrencyRate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * FX-002 목표 환율 알림 만들기·목록·지우기. 도달 판정(TRIGGERED 로 바꾸기)은 FxAlertTriggerService 가 한다.
 *
 * <p>트랜잭션을 걸지 않고 문장마다 커밋한다. 동시에 같은 조합을 만들면 늦은 쪽 INSERT 가 uq_fx_alerts_active 에서
 * 먼저 커밋된 행을 기다렸다가 실패하는데, 그 뒤 기존 알림 id 를 읽을 때 그 행이 보여야 하기 때문(트랜잭션 안이면
 * 먼저 만든 스냅샷이라 안 보임).
 */
@Service
public class FxAlertService {

    private final FxAlertRepository repository;

    public FxAlertService(FxAlertRepository repository) {
        this.repository = repository;
    }

    /**
     * 환율 행이 없는 통화는 404 FX_RATE_NOT_FOUND, 송금 보낼 때 환율이 없는 통화의 SEND 는 400,
     * 같은 통화·방향의 ACTIVE 알림이 있으면 409 DUPLICATE_REQUEST(details.alertId 가 기존 알림).
     */
    public FxAlertResponse create(long userId, FxAlertCreateRequest request) {
        String currency = request.currency();
        FxRateType rateType = request.rateType() == null ? FxRateType.SEND : request.rateType();
        CurrencyRate rate = repository.currencyRate(currency)
                .orElseThrow(() -> new BusinessException(ErrorCode.FX_RATE_NOT_FOUND));
        if (rateType == FxRateType.SEND && !rate.hasSendRate()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("reason", "SEND_RATE_NOT_AVAILABLE",
                    "fields", List.of(Map.of("field", "rateType", "reason",
                            currency + " has no send rate (" + rate.source() + " only), use BASE"))));
        }
        long alertId;
        try {
            alertId = repository.insert(userId, currency, rateType, request.comparator(), request.targetRate(),
                    Instant.now().truncatedTo(ChronoUnit.MILLIS));
        } catch (DuplicateKeyException e) {
            throw duplicate(repository.findActiveId(userId, currency, request.comparator()).orElse(null));
        }
        return FxAlertResponse.from(repository.find(userId, alertId).orElseThrow());
    }

    public FxAlertListResponse list(long userId) {
        return new FxAlertListResponse(repository.listVisible(userId).stream().map(FxAlertResponse::from).toList());
    }

    /** 행은 남기고 CANCELLED. 이미 CANCELLED 면 그대로 성공, 없거나 남의 알림이면 404(있는지 알려 주지 않음). */
    public void delete(long userId, long alertId) {
        if (repository.cancel(userId, alertId) == 0 && !repository.exists(userId, alertId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private static BusinessException duplicate(Long alertId) {
        Map<String, Object> details = new HashMap<>();
        details.put("alertId", alertId); // 그사이 기존 알림이 지워졌으면 null
        return new BusinessException(ErrorCode.DUPLICATE_REQUEST, details);
    }
}
