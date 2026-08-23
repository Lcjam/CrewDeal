package com.groupdrop.ledger;

import com.groupdrop.common.ApiException;
import com.groupdrop.user.User;
import com.groupdrop.user.UserRepository;
import com.groupdrop.user.UserRole;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예상 정산액 조회 (6.2 예상 커미션, 6.3 예상 공급 대금).
 *
 * <p>집계 원천은 <b>원장 하나</b>다 (LED-01, ADR-008). SET-01의 집계식으로 다시 계산하지 않는 이유는
 * 원천을 둘로 두면 라운딩 차이(`Σ round(xᵢ·r) ≠ round(Σxᵢ·r)`)만으로 정상 캠페인이 불일치로 보이기
 * 때문이다. 환불은 LED-03 역분개로 이미 잔액에 반영되어 있으므로 여기서 따로 빼지 않는다.
 *
 * <p>응답을 역할별로 나눈 것은 의도적이다 — 공급 단가는 공급사의 정보이고, 인플루언서에게 노출할
 * 이유가 없다.
 */
@Service
public class ExpectedSettlementService {

    private final LedgerRepository ledger;
    private final UserRepository users;

    public ExpectedSettlementService(LedgerRepository ledger, UserRepository users) {
        this.ledger = ledger;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public InfluencerDashboardResponse influencerDashboard(String requesterEmail, Long campaignId) {
        User requester = requireUser(requesterEmail, UserRole.INFLUENCER);
        LedgerRepository.CampaignParties parties = requireCampaign(campaignId);
        if (!parties.influencerUserId().equals(requester.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_NOT_OWNER", "본인 캠페인만 조회할 수 있습니다.");
        }
        Aggregate aggregate = aggregate(campaignId);
        return new InfluencerDashboardResponse(campaignId, parties.campaignName(), aggregate.settledOrderCount(),
                aggregate.refundedOrderCount(), aggregate.netSalesAmount(),
                aggregate.balance(LedgerAccount.INFLUENCER_PAYABLE));
    }

    @Transactional(readOnly = true)
    public SupplierExpectedSettlementResponse supplierExpectedSettlement(String requesterEmail, Long campaignId) {
        User requester = requireUser(requesterEmail, UserRole.SUPPLIER);
        LedgerRepository.CampaignParties parties = requireCampaign(campaignId);
        if (!parties.supplierUserId().equals(requester.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_NOT_OWNER", "본인 캠페인만 조회할 수 있습니다.");
        }
        Aggregate aggregate = aggregate(campaignId);
        return new SupplierExpectedSettlementResponse(campaignId, parties.campaignName(),
                aggregate.settledOrderCount(), aggregate.refundedOrderCount(),
                aggregate.balance(LedgerAccount.SUPPLIER_PAYABLE));
    }

    /** 운영자용 전체 분해. 12.4의 총합 항등식이 그대로 성립해야 하는 값들이다. */
    @Transactional(readOnly = true)
    public CampaignSettlementBreakdown breakdown(String requesterEmail, Long campaignId) {
        requireUser(requesterEmail, UserRole.ADMIN);
        requireCampaign(campaignId);
        Aggregate aggregate = aggregate(campaignId);
        return new CampaignSettlementBreakdown(campaignId, aggregate.settledOrderCount(),
                aggregate.refundedOrderCount(), aggregate.netSalesAmount(),
                aggregate.balance(LedgerAccount.SUPPLIER_PAYABLE),
                aggregate.balance(LedgerAccount.INFLUENCER_PAYABLE),
                aggregate.balance(LedgerAccount.PG_FEE_PAYABLE),
                aggregate.balance(LedgerAccount.PLATFORM_REVENUE));
    }

    private Aggregate aggregate(Long campaignId) {
        LedgerRepository.Counts counts = ledger.campaignCounts(campaignId);
        Map<String, Long> balances = ledger.campaignBalances(campaignId);
        // PG 미수금은 차변 계정이므로 잔액(대변 − 차변)의 부호를 뒤집어 순상품매출로 읽는다.
        long netSales = -balances.getOrDefault(LedgerAccount.PG_RECEIVABLE.name(), 0L);
        return new Aggregate(counts.paymentCount() - counts.refundCount(), counts.refundCount(),
                netSales, balances);
    }

    private LedgerRepository.CampaignParties requireCampaign(Long campaignId) {
        return ledger.findCampaignParties(campaignId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CAMPAIGN_NOT_FOUND",
                        "캠페인을 찾을 수 없습니다."));
    }

    private User requireUser(String email, UserRole role) {
        User user = users.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_USER_NOT_FOUND",
                        "사용자를 찾을 수 없습니다."));
        if (user.getRole() != role) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE", "이 조회를 수행할 권한이 없습니다.");
        }
        return user;
    }

    private record Aggregate(long settledOrderCount, long refundedOrderCount, long netSalesAmount,
                             Map<String, Long> balances) {

        long balance(LedgerAccount account) {
            return balances.getOrDefault(account.name(), 0L);
        }
    }

    public record InfluencerDashboardResponse(Long campaignId, String campaignName, long settledOrderCount,
                                              long refundedOrderCount, long netSalesAmount,
                                              long expectedCommission) { }

    public record SupplierExpectedSettlementResponse(Long campaignId, String campaignName, long settledOrderCount,
                                                     long refundedOrderCount, long expectedSupplyAmount) { }

    public record CampaignSettlementBreakdown(Long campaignId, long settledOrderCount, long refundedOrderCount,
                                              long netSalesAmount, long supplierPayable,
                                              long influencerCommission, long pgFee, long platformRevenue) { }
}
