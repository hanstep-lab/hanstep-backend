package com.hanstep.aicontext.service;

import com.hanstep.aicontext.dto.AiFinanceContextResponse;
import com.hanstep.commitment.service.CommitmentService;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.finance.service.FinanceAnalysisService;
import com.hanstep.goal.service.GoalService;
import com.hanstep.user.repository.UserRepository;
import org.springframework.stereotype.Service;

/** INT-06. FIN-06·CMT-01·GOL-01 서비스 결과를 그대로 모은다. */
@Service
public class AiContextService {

    private final UserRepository userRepository;
    private final FinanceAnalysisService financeAnalysisService;
    private final CommitmentService commitmentService;
    private final GoalService goalService;

    public AiContextService(UserRepository userRepository, FinanceAnalysisService financeAnalysisService,
                            CommitmentService commitmentService, GoalService goalService) {
        this.userRepository = userRepository;
        this.financeAnalysisService = financeAnalysisService;
        this.commitmentService = commitmentService;
        this.goalService = goalService;
    }

    public AiFinanceContextResponse finance(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return new AiFinanceContextResponse(userId,
                financeAnalysisService.findLatestCompleted(userId).orElse(null),
                commitmentService.getCommitments(userId).totals(),
                goalService.getGoals(userId).goals());
    }
}
