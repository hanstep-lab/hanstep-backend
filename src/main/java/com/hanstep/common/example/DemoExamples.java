package com.hanstep.common.example;

import com.hanstep.commitment.domain.CommitmentSource;
import com.hanstep.commitment.domain.CommitmentType;
import com.hanstep.commitment.dto.CommitmentListResponse;
import com.hanstep.commitment.dto.CommitmentResponse;
import com.hanstep.commitment.dto.CommitmentSaveRequest;
import com.hanstep.commitment.dto.CommitmentTotals;
import com.hanstep.commitment.dto.RemittanceCandidateListResponse;
import com.hanstep.commitment.dto.RemittanceCandidateResponse;
import com.hanstep.common.dto.Money;
import com.hanstep.consent.domain.ConsentStatus;
import com.hanstep.consent.domain.ConsentType;
import com.hanstep.consent.dto.ConsentListResponse;
import com.hanstep.consent.dto.ConsentResponse;
import com.hanstep.consent.dto.ConsentSaveRequest;
import com.hanstep.goal.domain.GoalStatus;
import com.hanstep.goal.domain.GoalType;
import com.hanstep.goal.dto.GoalListResponse;
import com.hanstep.goal.dto.GoalResponse;
import com.hanstep.goal.dto.GoalSaveRequest;
import com.hanstep.mydata.domain.ConnectionStatus;
import com.hanstep.mydata.domain.InstitutionType;
import com.hanstep.mydata.domain.MydataScope;
import com.hanstep.mydata.dto.ConnectionCreateRequest;
import com.hanstep.mydata.dto.ConnectionListResponse;
import com.hanstep.mydata.dto.ConnectionResponse;
import com.hanstep.mydata.dto.InstitutionListResponse;
import com.hanstep.mydata.dto.InstitutionResponse;
import com.hanstep.onboarding.domain.OnboardingStep;
import com.hanstep.onboarding.domain.OnboardingStepStatus;
import com.hanstep.onboarding.domain.ResidencePurpose;
import com.hanstep.onboarding.dto.OnboardingProfileRequest;
import com.hanstep.onboarding.dto.OnboardingProfileResponse;
import com.hanstep.onboarding.dto.OnboardingResponse;
import com.hanstep.onboarding.dto.OnboardingSummaryResponse;
import com.hanstep.onboarding.dto.StepUpdateRequest;
import com.hanstep.onboarding.dto.StepUpdateResponse;
import com.hanstep.user.domain.Language;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 온보딩 API 계약(Swagger) 단계의 고정 예시 응답. 데모 페르소나 Nguyen Van Minh(E-9, 베트남) 기준.
 *
 * <p>FE 가 실제 로직 전에 화면을 만들 수 있게 컨트롤러가 이 값을 그대로 돌려줌.
 * 각 API 의 서비스 로직을 구현하면 해당 메서드를 지우고, 모두 구현되면 이 클래스를 삭제함.
 */
public final class DemoExamples {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);

    public static final LocalDate STAY_EXPIRES_ON = LocalDate.of(2027, 10, 4);

    // 월 수지(KRW). 여유자금 = 소득 − 고정생활비 − 송금 − 변동지출
    private static final long MONTHLY_INCOME = 2_350_000;
    private static final long RENT = 400_000;
    private static final long UTILITY = 70_000;
    private static final long ELECTRICITY_WATER = 50_000;
    private static final long REMITTANCE_MOTHER = 500_000;
    private static final long REMITTANCE_SELF = 200_000;
    private static final long VARIABLE_EXPENSE = 450_000;

    private static final long FIXED_LIVING = RENT + UTILITY + ELECTRICITY_WATER;           // 520,000
    private static final long REMITTANCE = REMITTANCE_MOTHER + REMITTANCE_SELF;            // 700,000
    private static final long SPARE = MONTHLY_INCOME - FIXED_LIVING - REMITTANCE - VARIABLE_EXPENSE; // 680,000

    private DemoExamples() {
    }

    public static OffsetDateTime kst(int year, int month, int day, int hour, int minute, int second) {
        return OffsetDateTime.of(year, month, day, hour, minute, second, 0, KST_OFFSET);
    }

    public static OffsetDateTime nowKst() {
        return OffsetDateTime.now(KST).truncatedTo(ChronoUnit.SECONDS);
    }

    // ---- 온보딩 ----

    /** ON-001 체류 목적만 받음. 월소득은 MVP 에서 받지 않음(null). */
    public static OnboardingProfileResponse profile() {
        return new OnboardingProfileResponse(ResidencePurpose.WORK, null);
    }

    /** 언어·등록증까지 끝내고 마이데이터 동의 화면에 있는 상태. */
    public static OnboardingResponse onboarding() {
        List<OnboardingResponse.Step> steps = List.of(
                new OnboardingResponse.Step(OnboardingStep.LANGUAGE, OnboardingStepStatus.COMPLETED,
                        kst(2026, 11, 7, 10, 0, 41)),
                new OnboardingResponse.Step(OnboardingStep.ID_DOCUMENT, OnboardingStepStatus.COMPLETED,
                        kst(2026, 11, 7, 10, 3, 18)),
                new OnboardingResponse.Step(OnboardingStep.MYDATA_CONSENT, OnboardingStepStatus.IN_PROGRESS, null),
                new OnboardingResponse.Step(OnboardingStep.GOALS, OnboardingStepStatus.NOT_STARTED, null),
                new OnboardingResponse.Step(OnboardingStep.GOAL_DETAILS, OnboardingStepStatus.NOT_STARTED, null),
                new OnboardingResponse.Step(OnboardingStep.SUMMARY_CONFIRM, OnboardingStepStatus.NOT_STARTED, null));
        return new OnboardingResponse(false, OnboardingStep.MYDATA_CONSENT, steps, profile());
    }

    public static OnboardingSummaryResponse summary() {
        long daysRemaining = ChronoUnit.DAYS.between(LocalDate.now(KST), STAY_EXPIRES_ON);
        return new OnboardingSummaryResponse(
                new OnboardingSummaryResponse.User(1L, "NGUYEN VAN MINH", Language.en, "VNM"),
                new OnboardingSummaryResponse.Visa("E-9", "Non-professional Employment", STAY_EXPIRES_ON,
                        daysRemaining, 10),
                goals().goals(),
                commitments(),
                new OnboardingSummaryResponse.Balance(
                        Money.krw(MONTHLY_INCOME), Money.krw(FIXED_LIVING), Money.krw(REMITTANCE),
                        Money.krw(VARIABLE_EXPENSE), "TRANSACTIONS", Money.krw(SPARE), true));
    }

    // ---- 동의 ----

    public static ConsentListResponse consents() {
        return new ConsentListResponse(List.of(
                granted(1L, ConsentType.TERMS, "terms-2026-11", kst(2026, 11, 7, 10, 0, 12), null),
                granted(2L, ConsentType.PRIVACY, "privacy-2026-11", kst(2026, 11, 7, 10, 0, 12), null),
                granted(3L, ConsentType.ID_DOCUMENT, "id-document-2026-11", kst(2026, 11, 7, 10, 1, 5), null),
                granted(4L, ConsentType.MYDATA, "mydata-2026-11", kst(2026, 11, 7, 10, 4, 12),
                        kst(2027, 11, 7, 10, 4, 12)),
                granted(5L, ConsentType.MYDATA_TERMS, "mydata-terms-2026-11", kst(2026, 11, 7, 10, 4, 12),
                        null)));
    }

    /** CNS-02 저장 결과: 마이데이터 동의 2건. */
    public static ConsentListResponse savedMydataConsents() {
        OffsetDateTime now = nowKst();
        return new ConsentListResponse(List.of(
                granted(4L, ConsentType.MYDATA, "mydata-2026-11", now, now.plusYears(1)),
                granted(5L, ConsentType.MYDATA_TERMS, "mydata-terms-2026-11", now, null)));
    }

    private static ConsentResponse granted(Long id, ConsentType type, String version,
                                           OffsetDateTime grantedAt, OffsetDateTime expiresAt) {
        return new ConsentResponse(id, type, version, ConsentStatus.GRANTED, grantedAt, null, expiresAt);
    }

    // ---- 마이데이터 ----

    public static InstitutionListResponse institutions() {
        return new InstitutionListResponse(List.of(
                new InstitutionResponse(1L, "SHINHAN_BANK", "Shinhan Bank (Mock)", "신한은행",
                        InstitutionType.BANK, true),
                new InstitutionResponse(2L, "SHINHAN_CARD", "Shinhan Card (Mock)", "신한카드",
                        InstitutionType.CARD, true)));
    }

    public static ConnectionListResponse connections() {
        OffsetDateTime syncedAt = kst(2026, 11, 7, 10, 6, 3);
        return connections(syncedAt);
    }

    public static ConnectionListResponse connections(OffsetDateTime syncedAt) {
        return new ConnectionListResponse(true, List.of(
                new ConnectionResponse(1L, 1L, "SHINHAN_BANK", "신한은행", ConnectionStatus.CONNECTED,
                        List.of(MydataScope.ACCOUNT_BALANCE, MydataScope.TRANSACTIONS), 12, syncedAt, 2, 184, true),
                new ConnectionResponse(2L, 2L, "SHINHAN_CARD", "신한카드", ConnectionStatus.CONNECTED,
                        List.of(MydataScope.CARD_USAGE), 12, syncedAt, 1, 236, true)));
    }

    // ---- 목표 ----

    public static GoalListResponse goals() {
        return new GoalListResponse(List.of(
                new GoalResponse(1L, GoalType.LUMP_SUM, Money.krw(6_000_000), STAY_EXPIRES_ON, GoalStatus.ACTIVE,
                        Money.krw(600_000)),
                new GoalResponse(2L, GoalType.REMITTANCE, null, null, GoalStatus.ACTIVE, null),
                new GoalResponse(3L, GoalType.LIVING_COST, null, null, GoalStatus.ACTIVE, null)));
    }

    // ---- 필수 확보액 ----

    public static CommitmentListResponse commitments() {
        List<CommitmentResponse> items = List.of(
                new CommitmentResponse(1L, CommitmentType.RENT, "월세", Money.krw(RENT),
                        CommitmentSource.USER, null),
                new CommitmentResponse(2L, CommitmentType.UTILITY, "공과금", Money.krw(UTILITY),
                        CommitmentSource.USER, null),
                new CommitmentResponse(3L, CommitmentType.UTILITY, "전기·수도", Money.krw(ELECTRICITY_WATER),
                        CommitmentSource.USER, null),
                new CommitmentResponse(4L, CommitmentType.REMITTANCE, "가족 송금(Vietcombank)",
                        Money.krw(REMITTANCE_MOTHER), CommitmentSource.DETECTED_CONFIRMED, 1187L),
                new CommitmentResponse(5L, CommitmentType.REMITTANCE, "본인 계좌 송금(Techcombank)",
                        Money.krw(REMITTANCE_SELF), CommitmentSource.DETECTED_CONFIRMED, 1188L),
                new CommitmentResponse(6L, CommitmentType.VARIABLE_EXPENSE, "식비·교통·여가",
                        Money.krw(VARIABLE_EXPENSE), CommitmentSource.USER, null));
        CommitmentTotals totals = new CommitmentTotals(
                Money.krw(FIXED_LIVING), Money.krw(REMITTANCE), Money.krw(VARIABLE_EXPENSE), Money.krw(0),
                Money.krw(FIXED_LIVING + REMITTANCE + VARIABLE_EXPENSE));
        return new CommitmentListResponse(items, totals);
    }

    public static RemittanceCandidateListResponse remittanceCandidates() {
        return new RemittanceCandidateListResponse(List.of(
                new RemittanceCandidateResponse(1, "NGUYEN THI MAI", "Vietcombank", 1L,
                        Money.krw(REMITTANCE_MOTHER), 12, LocalDate.of(2026, 10, 10), 1187L),
                new RemittanceCandidateResponse(2, "NGUYEN VAN MINH", "Techcombank", null,
                        Money.krw(REMITTANCE_SELF), 12, LocalDate.of(2026, 10, 10), 1188L)),
                12, true);
    }

    // ---- 요청 예시(Swagger Example Value 용) ----

    public static OnboardingProfileRequest profileRequest() {
        return new OnboardingProfileRequest(ResidencePurpose.WORK, null);
    }

    public static StepUpdateRequest stepRequest() {
        return new StepUpdateRequest(OnboardingStepStatus.COMPLETED);
    }

    public static StepUpdateResponse stepResponse() {
        return new StepUpdateResponse(OnboardingStep.MYDATA_CONSENT, OnboardingStepStatus.COMPLETED,
                kst(2026, 11, 7, 10, 6, 5), false, OnboardingStep.GOALS);
    }

    public static ConsentSaveRequest consentRequest() {
        return new ConsentSaveRequest(List.of(
                new ConsentSaveRequest.Item(ConsentType.MYDATA, "mydata-2026-11", true),
                new ConsentSaveRequest.Item(ConsentType.MYDATA_TERMS, "mydata-terms-2026-11", true)));
    }

    public static ConnectionCreateRequest connectionRequest() {
        return new ConnectionCreateRequest(List.of(1L, 2L),
                List.of(MydataScope.ACCOUNT_BALANCE, MydataScope.TRANSACTIONS, MydataScope.CARD_USAGE), 12);
    }

    public static GoalSaveRequest goalRequest() {
        return new GoalSaveRequest(List.of(
                new GoalSaveRequest.Item(GoalType.LUMP_SUM, Money.krw(6_000_000), STAY_EXPIRES_ON),
                new GoalSaveRequest.Item(GoalType.REMITTANCE, null, null),
                new GoalSaveRequest.Item(GoalType.LIVING_COST, null, null)));
    }

    public static CommitmentSaveRequest commitmentRequest() {
        return new CommitmentSaveRequest(commitments().items().stream()
                .map(c -> new CommitmentSaveRequest.Item(c.commitmentType(), c.label(), c.amount(), c.source(),
                        c.detectedFromTxnId()))
                .toList());
    }
}
