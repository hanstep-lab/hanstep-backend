package com.hanstep.fx.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hanstep.common.time.KstDates;
import com.hanstep.fx.client.FxFetch.Rejection;
import com.hanstep.fx.config.FxCollectorProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 한국수출입은행 현재환율 Open API(data=AP01). 날짜(searchdate)마다 1번 부르면 고시 통화 23개가 한 번에 온다.
 * - HTTP 는 성공·실패 모두 200 이고, 항목의 result 로 구분한다(1 성공, 2 DATA 코드 오류, 3 인증키 오류, 4 일일 제한 초과)
 * - 숫자는 쉼표 있는 문자열("1,332.4"), cur_unit 에 고시 단위가 붙는다("JPY(100)")
 * - 영업일 11시 전후 고시. 그 전이나 비영업일은 빈 배열이고 "새 값 없음"이다
 * 시각을 주지 않아 rate_at 은 기준일 00:00 KST(하루 1건, exchange_rates 주석).
 */
@Component
public class KoreaeximClient {

    static final String PATH = "/site/program/financial/exchangeJSON";

    private static final DateTimeFormatter SEARCH_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern CUR_UNIT = Pattern.compile("([A-Z]{3})(?:\\((\\d+)\\))?");
    private static final String KRW = "KRW";

    private final RestClient restClient;
    private final FxCollectorProperties properties;

    public KoreaeximClient(@Qualifier("koreaeximRestClient") RestClient restClient, FxCollectorProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /** searchDate 고시 중 currencies 만. KRW 는 저장하지 않는다. 실패는 FxSourceException. */
    public FxFetch fetch(LocalDate searchDate, Collection<String> currencies) {
        if (!properties.koreaexim().hasApiKey()) {
            throw new FxSourceException("KOREAEXIM_API_KEY is not set", true);
        }
        List<Item> items = call(searchDate);
        if (items == null || items.isEmpty()) {
            return FxFetch.EMPTY;
        }
        for (Item item : items) {
            if (item == null || item.result() == null || item.result() != 1) {
                throw resultError(item == null ? null : item.result());
            }
        }

        Set<String> wanted = new LinkedHashSet<>(currencies);
        List<FxRate> rates = new ArrayList<>();
        List<Rejection> rejections = new ArrayList<>();
        for (Item item : items) {
            Matcher unit = item.curUnit() == null ? null : CUR_UNIT.matcher(item.curUnit().strip());
            if (unit == null || !unit.matches()) {
                continue; // 모르는 형식의 항목. 요청한 통화면 아래 "응답에 없음" 으로 남는다
            }
            String currency = unit.group(1);
            if (KRW.equals(currency) || !wanted.remove(currency)) {
                continue;
            }
            try {
                int unitAmount = unit.group(2) == null ? 1 : Integer.parseInt(unit.group(2));
                BigDecimal base = FxNumbers.parse(item.dealBasR());
                BigDecimal send = FxNumbers.parse(item.tts());
                BigDecimal receive = FxNumbers.parse(item.ttb());
                rates.add(new FxRate(currency, unitAmount, searchDate,
                        searchDate.atStartOfDay(KstDates.KST).toInstant(), base, send, receive));
            } catch (IllegalArgumentException e) {
                rejections.add(new Rejection(currency, e.getMessage()));
            }
        }
        wanted.forEach(c -> rejections.add(new Rejection(c, "not in KOREAEXIM response")));
        return new FxFetch(rates, rejections);
    }

    private List<Item> call(LocalDate searchDate) {
        try {
            return restClient.get()
                    .uri(b -> b.path(PATH)
                            .queryParam("authkey", properties.koreaexim().apiKey())
                            .queryParam("searchdate", SEARCH_DATE.format(searchDate))
                            .queryParam("data", "AP01")
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Item>>() {
                    });
        } catch (RestClientResponseException e) {
            throw new FxSourceException("HTTP " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            // 연결 실패·타임아웃. 메시지에 요청 URL(인증키 포함)이 있어 FxSourceException 이 가린다
            throw new FxSourceException("unreachable: " + e.getMessage());
        } catch (RestClientException e) {
            throw new FxSourceException("bad response: " + e.getMessage());
        }
    }

    private static FxSourceException resultError(Integer result) {
        String meaning = switch (result == null ? 0 : result) {
            case 2 -> "invalid data code";
            case 3 -> "invalid auth key";
            case 4 -> "daily call limit reached";
            default -> "unknown result";
        };
        // 인증키 오류·일일 한도는 다시 불러도 같다
        return new FxSourceException("result=" + result + " (" + meaning + ")", result != null && (result == 3 || result == 4));
    }

    /** 응답 항목. cur_nm·bkpr 등 쓰지 않는 칸은 무시한다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(Integer result,
                @JsonProperty("cur_unit") String curUnit,
                @JsonProperty("deal_bas_r") String dealBasR,
                String tts,
                String ttb) {
    }
}
