package com.groupdrop.ledger;

import com.groupdrop.common.ApiException;
import com.groupdrop.user.User;
import com.groupdrop.user.UserRepository;
import com.groupdrop.user.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 1단계 운영자 원장 조회. 원장은 읽기만 하며 상태나 분개를 바꾸지 않는다. */
@Service
public class LedgerAdminService {

    private static final int LIST_LIMIT = 100;

    private final LedgerRepository ledger;
    private final UserRepository users;

    public LedgerAdminService(LedgerRepository ledger, UserRepository users) {
        this.ledger = ledger;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public CampaignLedgerResponse campaign(String requesterEmail, Long campaignId) {
        requireAdmin(requesterEmail);
        LedgerRepository.EntryTotals totals = ledger.campaignEntryTotals(campaignId);
        return new CampaignLedgerResponse(campaignId, ledger.campaignBalances(campaignId), totals.debitTotal(),
                totals.creditTotal());
    }

    @Transactional(readOnly = true)
    public List<OrderPostingResponse> order(String requesterEmail, Long orderId) {
        requireAdmin(requesterEmail);
        return ledger.findOrderPostings(orderId).stream().map(posting -> new OrderPostingResponse(
                posting.transactionId(), posting.transactionType(), posting.referenceType(), posting.referenceId(),
                posting.occurredAt(), posting.accountCode(), posting.side(), posting.amount())).toList();
    }

    @Transactional(readOnly = true)
    public List<UnbalancedResponse> unbalanced(String requesterEmail) {
        requireAdmin(requesterEmail);
        return ledger.findUnbalancedTransactions().stream().limit(LIST_LIMIT)
                .map(item -> new UnbalancedResponse(item.transactionId(), item.debitTotal(), item.creditTotal()))
                .toList();
    }

    private void requireAdmin(String email) {
        User user = users.findByEmail(email).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                "AUTH_USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));
        if (user.getRole() != UserRole.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE", "이 작업을 수행할 권한이 없습니다.");
        }
    }

    public record CampaignLedgerResponse(Long campaignId, Map<String, Long> balances, long debitTotal,
                                         long creditTotal) { }

    public record OrderPostingResponse(Long transactionId, String transactionType, String referenceType,
                                       Long referenceId, Instant occurredAt, String accountCode, String side,
                                       long amount) { }

    public record UnbalancedResponse(Long transactionId, long debitTotal, long creditTotal) { }
}
