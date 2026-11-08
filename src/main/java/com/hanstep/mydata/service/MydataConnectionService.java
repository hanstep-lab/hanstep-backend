package com.hanstep.mydata.service;

import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.consent.domain.ConsentStatus;
import com.hanstep.consent.domain.ConsentType;
import com.hanstep.consent.domain.UserConsent;
import com.hanstep.consent.repository.UserConsentRepository;
import com.hanstep.mydata.domain.ConnectionStatus;
import com.hanstep.mydata.domain.DataConnection;
import com.hanstep.mydata.domain.Institution;
import com.hanstep.mydata.domain.InstitutionType;
import com.hanstep.mydata.domain.MydataScope;
import com.hanstep.mydata.dto.ConnectionCreateRequest;
import com.hanstep.mydata.dto.ConnectionListResponse;
import com.hanstep.mydata.dto.ConnectionResponse;
import com.hanstep.mydata.dto.InstitutionListResponse;
import com.hanstep.mydata.dto.InstitutionResponse;
import com.hanstep.mydata.repository.DataConnectionRepository;
import com.hanstep.mydata.repository.FinancialAccountRepository;
import com.hanstep.mydata.repository.FinancialTransactionRepository;
import com.hanstep.mydata.repository.InstitutionRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * MYD-01 기관 목록, MYD-02 연결·적재, MYD-03 연결 상태.
 * MYD-02 는 검사(404·403·키 형식) 후 적재를 맡기고, MYD-03 과 같은 연결 상태 전체를 돌려줌.
 */
@Service
public class MydataConnectionService {

    private static final int DEFAULT_LOOKBACK_MONTHS = 12;
    private static final int MAX_KEY_LENGTH = 100; // ingestion_batches.request_key
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    // ON-003A·003B 화면의 기관(신한은행·신한카드). MOCK_TELECOM 등은 목록에서 뺌
    private static final List<InstitutionType> LISTED_TYPES = List.of(InstitutionType.BANK, InstitutionType.CARD);

    private final InstitutionRepository institutionRepository;
    private final UserConsentRepository consentRepository;
    private final DataConnectionRepository connectionRepository;
    private final FinancialAccountRepository accountRepository;
    private final FinancialTransactionRepository transactionRepository;
    private final MockMydataReader reader;
    private final MockIngestionService ingestionService;

    public MydataConnectionService(InstitutionRepository institutionRepository,
                                   UserConsentRepository consentRepository,
                                   DataConnectionRepository connectionRepository,
                                   FinancialAccountRepository accountRepository,
                                   FinancialTransactionRepository transactionRepository,
                                   MockMydataReader reader,
                                   MockIngestionService ingestionService) {
        this.institutionRepository = institutionRepository;
        this.consentRepository = consentRepository;
        this.connectionRepository = connectionRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.reader = reader;
        this.ingestionService = ingestionService;
    }

    /** MYD-01. institutions 중 BANK·CARD 업권만(ID 순). MVP 기관은 모두 Mock. */
    public InstitutionListResponse institutions() {
        return new InstitutionListResponse(institutionRepository.findAllByInstitutionTypeInOrderById(LISTED_TYPES)
                .stream()
                .map(i -> new InstitutionResponse(i.getId(), i.getInstitutionCode(), i.getName(), i.getNameKo(),
                        i.getInstitutionType(), true))
                .toList());
    }

    /** created=false 면 같은 Idempotency-Key 가 이미 처리된 재요청(200). */
    public ConnectResult connect(Long userId, String idempotencyKey, ConnectionCreateRequest request) {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, null, Map.of("fields",
                    List.of(Map.of("field", "Idempotency-Key", "reason", "must be 1~100 characters"))));
        }
        List<Institution> institutions = findInstitutions(request.institutionIds());
        Long consentId = requireMydataConsent(userId);
        int lookbackMonths = request.lookbackMonths() == null ? DEFAULT_LOOKBACK_MONTHS : request.lookbackMonths();
        List<MydataScope> scopes = List.copyOf(new LinkedHashSet<>(request.scopes()));

        List<MockIngestionService.Target> targets = institutions.stream()
                .map(i -> new MockIngestionService.Target(i.getId(), reader.read(i.getInstitutionCode()).orElse(null)))
                .toList();
        boolean created = ingestionService.ingest(userId, idempotencyKey, consentId, scopes, lookbackMonths, targets);
        return new ConnectResult(connections(userId), created);
    }

    /** MYD-03. 사용자의 연결 상태 전체(MYD-02 응답과 같음). 계좌·거래 수는 DB 에서 집계. 연결한 적 없으면 빈 목록. */
    public ConnectionListResponse connections(Long userId) {
        List<DataConnection> rows = connectionRepository.findAllByUserIdOrderById(userId);
        Map<Long, Institution> institutions = institutionRepository
                .findAllById(rows.stream().map(DataConnection::getInstitutionId).toList()).stream()
                .collect(Collectors.toMap(Institution::getId, Function.identity()));
        List<ConnectionResponse> connections = new ArrayList<>();
        for (DataConnection c : rows) {
            Institution institution = institutions.get(c.getInstitutionId());
            connections.add(new ConnectionResponse(c.getId(), institution.getId(), institution.getInstitutionCode(),
                    institution.getNameKo() != null ? institution.getNameKo() : institution.getName(),
                    c.getStatus(), scopes(c.getRequestedScopesJson()), c.getLookbackMonths(),
                    toKst(c.getLastSyncedAt()),
                    (int) accountRepository.countByUserIdAndInstitutionId(userId, institution.getId()),
                    (int) transactionRepository.countByUserIdAndInstitutionId(userId, institution.getId()),
                    true));
        }
        boolean connected = rows.stream().anyMatch(c -> c.getStatus() == ConnectionStatus.CONNECTED);
        return new ConnectionListResponse(connected, connections);
    }

    /** 없는 기관 ID 가 하나라도 있으면 404. 요청 순서를 지킴. */
    private List<Institution> findInstitutions(List<Long> ids) {
        List<Long> distinct = List.copyOf(new LinkedHashSet<>(ids));
        Map<Long, Institution> found = institutionRepository.findAllById(distinct).stream()
                .collect(Collectors.toMap(Institution::getId, Function.identity()));
        if (found.size() != distinct.size()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return distinct.stream().map(found::get).toList();
    }

    /** MYDATA·MYDATA_TERMS 의 최신 행이 모두 GRANTED 여야 함. data_connections.consent_id 에는 MYDATA 동의를 넣음. */
    private Long requireMydataConsent(Long userId) {
        Map<ConsentType, UserConsent> latest = new EnumMap<>(ConsentType.class);
        for (UserConsent consent : consentRepository.findAllByUserIdOrderByIdDesc(userId)) {
            latest.putIfAbsent(consent.getConsentType(), consent);
        }
        for (ConsentType type : List.of(ConsentType.MYDATA, ConsentType.MYDATA_TERMS)) {
            UserConsent consent = latest.get(type);
            if (consent == null || consent.getStatus() != ConsentStatus.GRANTED) {
                throw new BusinessException(ErrorCode.CONSENT_REQUIRED, Map.of("consentType", type.name()));
            }
        }
        return latest.get(ConsentType.MYDATA).getId();
    }

    private static List<MydataScope> scopes(String json) {
        return json == null ? List.of() : List.of(MAPPER.readValue(json, MydataScope[].class));
    }

    private static OffsetDateTime toKst(Instant at) {
        return at == null ? null : at.atOffset(ZoneOffset.ofHours(9));
    }

    public record ConnectResult(ConnectionListResponse body, boolean created) {
    }
}
