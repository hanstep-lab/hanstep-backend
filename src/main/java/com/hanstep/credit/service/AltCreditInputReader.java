package com.hanstep.credit.service;

import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.common.time.KstDates;
import com.hanstep.credit.dto.AltCreditInputs;
import com.hanstep.mydata.domain.BillType;
import com.hanstep.mydata.repository.BillPaymentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 대안신용 모의 사전진단 입력을 MySQL 에서 모은다(저장하지 않음).
 * <ul>
 *   <li>체류(visa_profiles): 누적 체류 개월, 만료까지 개월, 근로 가능 여부. 기준일은 오늘(KST)</li>
 *   <li>Mock 거래·계좌: 가장 최근 SUCCEEDED 수집 배치의 기간을 온전한 달로 자르고 끝에서 최대 12개월(FIN-05 와 같은 자르기).
 *       소득·송금은 INT-01 처럼 NORMAL 거래만 센다(소득 = 입금, 송금 = REMITTANCE 출금)</li>
 *   <li>청구 납부(mydata_bill_payments): 기준 월까지 6개 청구 월 중 연체가 있었던 달 수</li>
 * </ul>
 * 기준 월은 집계 기간의 마지막 달이다. 수집 기간 끝을 따르므로 오늘의 전달과 다를 수 있다(Reyes Mock 은 2026-10 고정).
 */
@Component
public class AltCreditInputReader {

    static final int MAX_MONTHS = 12;
    static final int RECENT_MONTHS = 6;
    static final int RATIO_SCALE = 4;

    // 취업 가능 체류자격(임시 기준). D-2 등은 시간제취업 허가 정보가 아직 없어 false 로 본다
    static final Set<String> WORK_PERMITTED_VISAS = Set.of("E-9", "E-7-4", "H-2", "F-2");

    private static final List<BillType> TELECOM = List.of(BillType.TELECOM);
    private static final List<BillType> UTILITIES = List.of(BillType.HEALTH_INSURANCE, BillType.UTILITY);

    private final JdbcTemplate jdbcTemplate;
    private final BillPaymentRepository billPaymentRepository;

    public AltCreditInputReader(JdbcTemplate jdbcTemplate, BillPaymentRepository billPaymentRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.billPaymentRepository = billPaymentRepository;
    }

    /**
     * 입력이 모자라면 422 INSUFFICIENT_DATA. details.reason 은 NO_VISA_PROFILE·NO_STAY_START_DATE(체류),
     * NO_SUCCEEDED_BATCH·NO_COVERAGE·NO_FULL_MONTH·NO_TRANSACTIONS(Mock 거래).
     */
    public AltCreditInputs read(Long userId, LocalDate today, boolean isRefinance) {
        Stay stay = stay(userId, today);
        Window window = window(userId);
        Map<YearMonth, MonthTotals> totals = monthTotals(userId, window);
        if (totals.isEmpty()) {
            throw insufficient("NO_TRANSACTIONS");
        }

        List<YearMonth> months = window.months();
        long income = 0;
        long remittance = 0;
        for (MonthTotals month : totals.values()) {
            income += month.income();
            remittance += month.remittance();
        }
        long monthlyIncome = BigDecimal.valueOf(income)
                .divide(BigDecimal.valueOf(months.size()), 0, RoundingMode.HALF_UP).longValueExact();

        // 기준 월부터 거꾸로 급여 입금이 이어진 달 수(집계 기간이 최대 12개월이라 12 를 넘지 않음)
        int salaryMonths = 0;
        for (int i = months.size() - 1; i >= 0; i--) {
            if (!totals.getOrDefault(months.get(i), MonthTotals.EMPTY).salary()) {
                break;
            }
            salaryMonths++;
        }

        YearMonth last = months.get(months.size() - 1);
        YearMonth recentFrom = last.minusMonths(RECENT_MONTHS - 1);
        int remitMonths = 0;
        for (YearMonth month = recentFrom; !month.isAfter(last); month = month.plusMonths(1)) {
            if (totals.getOrDefault(month, MonthTotals.EMPTY).remittance() > 0) {
                remitMonths++;
            }
        }

        AccountFlags accounts = accountFlags(userId);
        LocalDate recentStart = recentFrom.atDay(1);
        LocalDate lastStart = last.atDay(1);
        int telLate = Math.toIntExact(
                billPaymentRepository.countLateMonths(userId, TELECOM, recentStart, lastStart, today));
        int utilLate = Math.toIntExact(
                billPaymentRepository.countLateMonths(userId, UTILITIES, recentStart, lastStart, today));

        return new AltCreditInputs(stay.durationMonths(), stay.monthsToExpiry(), stay.workPermitted(), monthlyIncome,
                salaryMonths, accounts.shinhanPayroll(), accounts.installment(), isRefinance, telLate, utilLate,
                remitRatio(remittance, income), remitMonths == 0 ? null : remitMonths, today, window.from(),
                window.to(), recentStart);
    }

    private record Stay(int durationMonths, Integer monthsToExpiry, boolean workPermitted) {
    }

    private record VisaRow(String visaTypeCode, LocalDate stayStartedOn, Integer priorStayMonths,
                           LocalDate stayExpiresOn) {
    }

    private Stay stay(Long userId, LocalDate today) {
        VisaRow visa = jdbcTemplate.query("""
                                SELECT vt.code AS visa_type_code, vp.current_stay_started_on, vp.prior_stay_months,
                                       vp.stay_expires_on
                                FROM visa_profiles vp LEFT JOIN visa_types vt ON vt.id = vp.visa_type_id
                                WHERE vp.user_id = ?""",
                        (rs, i) -> new VisaRow(rs.getString("visa_type_code"),
                                rs.getObject("current_stay_started_on", LocalDate.class),
                                rs.getObject("prior_stay_months", Integer.class),
                                rs.getObject("stay_expires_on", LocalDate.class)),
                        userId)
                .stream().findFirst()
                .orElseThrow(() -> insufficient("NO_VISA_PROFILE"));
        if (visa.stayStartedOn() == null) {
            throw insufficient("NO_STAY_START_DATE");
        }
        int prior = visa.priorStayMonths() == null ? 0 : visa.priorStayMonths();
        Integer toExpiry = visa.stayExpiresOn() == null ? null : fullMonths(today, visa.stayExpiresOn());
        boolean work = visa.visaTypeCode() != null && WORK_PERMITTED_VISAS.contains(visa.visaTypeCode());
        return new Stay(fullMonths(visa.stayStartedOn(), today) + prior, toExpiry, work);
    }

    // from~to 의 꽉 찬 달 수(내림). to 가 from 이전이면 0(만료일이 지났으면 남은 개월 0)
    static int fullMonths(LocalDate from, LocalDate to) {
        return to.isBefore(from) ? 0 : Math.toIntExact(Period.between(from, to).toTotalMonths());
    }

    /** 거래 집계 기간(온전한 달력 월, 양 끝 포함). */
    record Window(LocalDate from, LocalDate to) {

        List<YearMonth> months() {
            YearMonth first = YearMonth.from(from);
            YearMonth last = YearMonth.from(to);
            List<YearMonth> months = new ArrayList<>();
            for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
                months.add(month);
            }
            return months;
        }
    }

    private record Coverage(LocalDate from, LocalDate to) {
    }

    private Window window(Long userId) {
        Coverage coverage = jdbcTemplate.query("""
                                SELECT coverage_from, coverage_to FROM ingestion_batches
                                WHERE user_id = ? AND status = 'SUCCEEDED' ORDER BY id DESC LIMIT 1""",
                        (rs, i) -> new Coverage(rs.getObject("coverage_from", LocalDate.class),
                                rs.getObject("coverage_to", LocalDate.class)),
                        userId)
                .stream().findFirst()
                .orElseThrow(() -> insufficient("NO_SUCCEEDED_BATCH"));
        if (coverage.from() == null || coverage.to() == null) {
            throw insufficient("NO_COVERAGE");
        }
        return window(coverage.from(), coverage.to());
    }

    /** 수집 기간을 온전한 달력 월로 자르고 끝에서 최대 12개월. 꽉 찬 달이 없으면 422 NO_FULL_MONTH. */
    static Window window(LocalDate coverageFrom, LocalDate coverageTo) {
        LocalDate from = KstDates.firstFullMonthStart(coverageFrom);
        LocalDate to = KstDates.lastFullMonthEnd(coverageTo);
        if (from.isAfter(to)) {
            throw insufficient("NO_FULL_MONTH");
        }
        LocalDate earliest = to.plusDays(1).minusMonths(MAX_MONTHS);
        return new Window(from.isBefore(earliest) ? earliest : from, to);
    }

    /** 달별 소득·송금 합(원)과 급여 입금 유무. NORMAL 거래가 있는 달만 담긴다. */
    private record MonthTotals(long income, long remittance, boolean salary) {

        static final MonthTotals EMPTY = new MonthTotals(0, 0, false);
    }

    private Map<YearMonth, MonthTotals> monthTotals(Long userId, Window window) {
        Map<YearMonth, MonthTotals> totals = new HashMap<>();
        jdbcTemplate.query("""
                        SELECT YEAR(booked_date) AS y, MONTH(booked_date) AS m,
                               SUM(CASE WHEN direction = 'INCOME' THEN amount ELSE 0 END) AS income,
                               SUM(CASE WHEN direction = 'EXPENSE' AND category_code = 'REMITTANCE'
                                        THEN amount ELSE 0 END) AS remittance,
                               MAX(direction = 'INCOME' AND category_code = 'SALARY') AS salary
                        FROM financial_transactions
                        WHERE user_id = ? AND currency = 'KRW' AND transaction_kind = 'NORMAL'
                          AND booked_date BETWEEN ? AND ?
                        GROUP BY y, m""",
                rs -> {
                    totals.put(YearMonth.of(rs.getInt("y"), rs.getInt("m")),
                            new MonthTotals(won(rs.getBigDecimal("income")), won(rs.getBigDecimal("remittance")),
                                    rs.getBoolean("salary")));
                },
                userId, window.from(), window.to());
        return totals;
    }

    private record AccountFlags(boolean shinhanPayroll, boolean installment) {
    }

    // 해지 안 된 계좌 기준. 신한 급여 계좌 = 신한은행의 급여 계좌, 청약 = 적금(INSTALLMENT) 계좌(임시 기준)
    private AccountFlags accountFlags(Long userId) {
        return jdbcTemplate.queryForObject("""
                        SELECT COALESCE(MAX(a.is_salary_account AND i.institution_code = 'SHINHAN_BANK'), 0)
                                   AS shinhan_payroll,
                               COALESCE(MAX(a.account_type = 'INSTALLMENT'), 0) AS installment
                        FROM financial_accounts a JOIN institutions i ON i.id = a.institution_id
                        WHERE a.user_id = ? AND a.closed_on IS NULL""",
                (rs, i) -> new AccountFlags(rs.getBoolean("shinhan_payroll"), rs.getBoolean("installment")),
                userId);
    }

    // 송금 합 ÷ 소득 합, 소수 4자리. AI 는 0~1 만 받으므로 1 을 넘으면 1(0.8 초과는 AI 가 잘라 inputWarnings 에 알림)
    static BigDecimal remitRatio(long remittance, long income) {
        if (income <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal ratio = BigDecimal.valueOf(remittance).divide(BigDecimal.valueOf(income), RATIO_SCALE,
                RoundingMode.HALF_UP);
        return ratio.min(BigDecimal.ONE);
    }

    // DECIMAL 합을 원 단위 정수로. 소수가 있으면 데이터 오류로 본다(금융 분석과 같은 규칙)
    private static long won(BigDecimal amount) {
        try {
            return amount.longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalStateException("transaction amount is not whole KRW", e);
        }
    }

    private static BusinessException insufficient(String reason) {
        return new BusinessException(ErrorCode.INSUFFICIENT_DATA, Map.of("reason", reason));
    }
}
