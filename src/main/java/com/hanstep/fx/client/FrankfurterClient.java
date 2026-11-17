package com.hanstep.fx.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hanstep.fx.client.FxFetch.Rejection;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

/**
 * Frankfurter v2 의 ECB 기준환율(기관 ECB 고정, 키 없음). base=PHP&quotes=KRW 는 1 PHP 의 원화 값이다.
 * 응답: [{"date":"2026-11-16","base":"PHP","quote":"KRW","rate":20.872}]. 영업일만 있고 값은 하루 1번 바뀐다.
 * 여러 기관을 합친 종합 환율(/v2/rates)은 기관마다 날짜가 달라 쓰지 않는다.
 */
@Component
public class FrankfurterClient {

    public static final String PROVIDER = "frankfurter-ecb";

    static final String PATH = "/providers/ecb/rates";
    private static final String KRW = "KRW";

    // 응답에 시각이 없어 rate_at 은 그 날짜의 ECB 기준환율 고시 시각(프랑크푸르트 16:00, 서머타임 반영)을 UTC 로 바꾼 값이다.
    // ECB 는 영업일 16:00 CET 무렵 기준환율을 발표한다. 화면은 날짜(rate_date)만 쓴다
    static final LocalTime ECB_PUBLISH_TIME = LocalTime.of(16, 0);
    static final ZoneId ECB_ZONE = ZoneId.of("Europe/Berlin");

    private final RestClient restClient;

    public FrankfurterClient(@Qualifier("frankfurterRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /** 가장 최근 ECB 기준일 값 1건. */
    public FxFetch latest(String currency) {
        return fetch(currency, b -> b);
    }

    /** from~to(포함) 영업일 값. 1번 호출로 기간 전체가 온다. */
    public FxFetch range(String currency, LocalDate from, LocalDate to) {
        return fetch(currency, b -> b.queryParam("from", from).queryParam("to", to));
    }

    /** ECB 기준일의 고시 시각(UTC). rate_date 는 원천 날짜(ECB 기준일)를 그대로 쓴다. */
    static Instant rateAt(LocalDate date) {
        return date.atTime(ECB_PUBLISH_TIME).atZone(ECB_ZONE).toInstant();
    }

    private FxFetch fetch(String currency, UnaryOperator<UriBuilder> period) {
        List<Item> items = call(currency, period);
        if (items == null || items.isEmpty()) {
            return FxFetch.EMPTY;
        }
        List<FxRate> rates = new ArrayList<>();
        List<Rejection> rejections = new ArrayList<>();
        for (Item item : items) {
            if (item == null || item.date() == null || !currency.equals(item.base()) || !KRW.equals(item.quote())) {
                throw new FxSourceException("unexpected item " + item);
            }
            try {
                BigDecimal rate = FxNumbers.check(item.rate());
                rates.add(new FxRate(currency, 1, item.date(), rateAt(item.date()), rate, null, null));
            } catch (IllegalArgumentException e) {
                rejections.add(new Rejection(currency, item.date() + " " + e.getMessage()));
            }
        }
        return new FxFetch(rates, rejections);
    }

    private List<Item> call(String currency, UnaryOperator<UriBuilder> period) {
        try {
            return restClient.get()
                    .uri(b -> period.apply(b.path(PATH).queryParam("base", currency).queryParam("quotes", KRW)).build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Item>>() {
                    });
        } catch (RestClientResponseException e) {
            throw new FxSourceException("HTTP " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            throw new FxSourceException("unreachable: " + e.getMessage());
        } catch (RestClientException e) {
            throw new FxSourceException("bad response: " + e.getMessage());
        }
    }

    /** rate 는 BigDecimal 로 받아 그대로 저장한다(반올림 금지). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(LocalDate date, String base, String quote, BigDecimal rate) {
    }
}
