package com.hanstep.aicontext.service;

import com.hanstep.aicontext.dto.AiFinanceContextResponse;
import com.hanstep.aicontext.dto.AiFxSummaryResponse;
import com.hanstep.aicontext.dto.AiFxSummaryResponse.CurrencySummary;
import com.hanstep.aicontext.dto.AiProductProfileResponse;
import com.hanstep.commitment.dto.CommitmentListResponse;
import com.hanstep.commitment.service.CommitmentService;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.common.time.KstDates;
import com.hanstep.finance.service.FinanceAnalysisService;
import com.hanstep.finance.service.MonthlyBalanceService;
import com.hanstep.fx.service.FxRateService;
import com.hanstep.goal.service.GoalService;
import com.hanstep.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** INT-06. FIN-06·CMT-01·GOL-01 서비스 결과를 그대로 모은다. INT-19. 상품 추천용 비자·나이 프로필. */
@Service
public class AiContextService {

    private final UserRepository userRepository;
    private final FinanceAnalysisService financeAnalysisService;
    private final MonthlyBalanceService monthlyBalanceService;
    private final CommitmentService commitmentService;
    private final GoalService goalService;
    private final JdbcTemplate jdbcTemplate;
    private final FxRateService fxRateService;

    public AiContextService(UserRepository userRepository, FinanceAnalysisService financeAnalysisService,
                            MonthlyBalanceService monthlyBalanceService,
                            CommitmentService commitmentService, GoalService goalService,
                            JdbcTemplate jdbcTemplate, FxRateService fxRateService) {
        this.userRepository = userRepository;
        this.financeAnalysisService = financeAnalysisService;
        this.monthlyBalanceService = monthlyBalanceService;
        this.commitmentService = commitmentService;
        this.goalService = goalService;
        this.jdbcTemplate = jdbcTemplate;
        this.fxRateService = fxRateService;
    }

    public AiFinanceContextResponse finance(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        CommitmentListResponse commitments = commitmentService.getCommitments(userId);
        return new AiFinanceContextResponse(userId,
                financeAnalysisService.findLatestCompleted(userId).orElse(null),
                monthlyBalanceService.balance(userId, commitments),
                commitments.totals(),
                goalService.getGoals(userId).goals());
    }

    /**
     * 챗봇 환율 진단(AI fx_rate_summary). 통화별 값은 환율 화면과 같은 계산(FxRateService.rate)을 그대로 쓴다.
     * currency 를 주면 그 통화만(화면 통화가 아니면 400, 실데이터가 없으면 404 FX_RATE_NOT_FOUND). 생략하면 화면 통화 전부이고,
     * 실데이터가 없는 통화는 unavailableCurrencies 로 빼며 하나도 없으면 404. 없는 사용자는 404 USER_NOT_FOUND.
     */
    public AiFxSummaryResponse fxSummary(Long userId, String currency) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        List<String> screen = fxRateService.screenCurrencies(userId);
        if (currency != null && !currency.isBlank()) {
            String target = currency.trim().toUpperCase(Locale.ROOT);
            return new AiFxSummaryResponse(userId, screen, List.of(CurrencySummary.from(fxRateService.rate(userId, target))),
                    List.of());
        }
        List<CurrencySummary> found = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (String c : screen) {
            try {
                found.add(CurrencySummary.from(fxRateService.rate(userId, c)));
            } catch (BusinessException e) {
                if (e.getErrorCode() != ErrorCode.FX_RATE_NOT_FOUND) {
                    throw e;
                }
                unavailable.add(c);
            }
        }
        if (found.isEmpty()) {
            throw new BusinessException(ErrorCode.FX_RATE_NOT_FOUND);
        }
        return new AiFxSummaryResponse(userId, screen, found, unavailable);
    }

    /** 비자 프로필이 없으면 asOf 외 모든 값이 null(404 아님). 생년월일은 만 나이로만 내보낸다. */
    public AiProductProfileResponse productProfile(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        LocalDate asOf = LocalDate.now(KstDates.KST);
        List<AiProductProfileResponse> rows = jdbcTemplate.query("""
                        SELECT vt.code AS visa_type_code, vp.stay_expires_on, vp.birth_date
                        FROM visa_profiles vp LEFT JOIN visa_types vt ON vt.id = vp.visa_type_id
                        WHERE vp.user_id = ?""",
                (rs, i) -> new AiProductProfileResponse(rs.getString("visa_type_code"),
                        rs.getObject("stay_expires_on", LocalDate.class),
                        ageOn(rs.getObject("birth_date", LocalDate.class), asOf),
                        asOf),
                userId);
        return rows.isEmpty() ? new AiProductProfileResponse(null, null, null, asOf) : rows.getFirst();
    }

    /** asOf 날짜 기준 만 나이(생일 당일에 한 살 늘어난다). 생년월일이 없으면 null. */
    static Integer ageOn(LocalDate birthDate, LocalDate asOf) {
        return birthDate == null ? null : Period.between(birthDate, asOf).getYears();
    }
}
