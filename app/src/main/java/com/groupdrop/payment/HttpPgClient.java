package com.groupdrop.payment;

import com.groupdrop.common.GroupdropProperties;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** 가상 PG의 confirm 호출. 같은 merchantPaymentId 재호출은 PG 쪽 멱등이므로 UNKNOWN 해소에도 그대로 쓴다 (14.5). */
@Component
public class HttpPgClient implements PgClient {

    private static final Logger log = LoggerFactory.getLogger(HttpPgClient.class);

    private final RestClient restClient;

    public HttpPgClient(GroupdropProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.pg().requestTimeout());
        factory.setReadTimeout(properties.pg().requestTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.pg().baseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public ConfirmResult confirm(ConfirmCommand command) {
        try {
            ConfirmApiResponse response = restClient.post()
                    .uri("/mock-pg/payments/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ConfirmApiRequest(command.merchantPaymentId(),
                            String.valueOf(command.orderId()), command.amount()))
                    .retrieve()
                    .body(ConfirmApiResponse.class);
            return interpret(response);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                return ConfirmResult.failed("PG_REJECTED", exception.getStatusText());
            }
            log.warn("PG confirm이 5xx로 응답해 결과를 확정할 수 없습니다: {}", command.merchantPaymentId(), exception);
            return ConfirmResult.timeout("PG 5xx: " + exception.getStatusCode());
        } catch (RestClientException exception) {
            log.warn("PG confirm 응답을 받지 못했습니다: {}", command.merchantPaymentId(), exception);
            return ConfirmResult.timeout(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    @Override
    public RefundResult refund(RefundCommand command) {
        try {
            RefundApiResponse response = restClient.post()
                    .uri("/mock-pg/payments/{providerPaymentId}/refund", command.providerPaymentId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new RefundApiRequest(command.merchantRefundId(), command.amount()))
                    .retrieve()
                    .body(RefundApiResponse.class);
            return interpret(response);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                // 4xx는 PG가 환불을 명시적으로 거부한 것이다 (없는 결제, 환불 불가 상태 등).
                return RefundResult.failed("PG_REFUND_REJECTED", exception.getStatusText());
            }
            log.warn("PG 환불이 5xx로 응답해 결과를 확정할 수 없습니다: {}", command.providerPaymentId(), exception);
            return RefundResult.timeout("PG 5xx: " + exception.getStatusCode());
        } catch (RestClientException exception) {
            log.warn("PG 환불 응답을 받지 못했습니다: {}", command.providerPaymentId(), exception);
            return RefundResult.timeout(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    /**
     * 목록 조회 실패는 예외로 던진다. 빈 목록을 돌려주면 대사가 "PG에 아무 거래도 없다"로 읽어
     * 내부 결제 전부를 MISSING_PROVIDER로 분류하고 정산을 통째로 HELD시킨다.
     */
    @Override
    public List<ProviderTransaction> listTransactions(Instant processedBefore) {
        try {
            TransactionsApiResponse response = restClient.get()
                    .uri(builder -> builder.path("/mock-pg/reconciliation/transactions")
                            .queryParam("createdBefore", processedBefore.toString())
                            .build())
                    .retrieve()
                    .body(TransactionsApiResponse.class);
            if (response == null || response.transactions() == null) {
                throw new IllegalStateException("PG 대사 목록 응답 본문이 비어 있습니다.");
            }
            return response.transactions().stream().map(this::toTransaction).toList();
        } catch (RestClientException exception) {
            log.error("PG 대사 목록을 가져오지 못했습니다.", exception);
            throw new IllegalStateException("PG 대사 목록 조회에 실패했습니다: " + exception.getMessage(), exception);
        }
    }

    private ProviderTransaction toTransaction(TransactionApiItem item) {
        return new ProviderTransaction(item.providerPaymentId(), item.merchantPaymentId(), item.orderId(),
                item.amount() == null ? 0L : item.amount(), item.status(), parse(item.processedAt()),
                item.refundedAmount() == null ? 0L : item.refundedAmount(), item.providerRefundId(),
                parse(item.refundedAt()));
    }

    private Instant parse(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private RefundResult interpret(RefundApiResponse response) {
        if (response == null || response.status() == null) {
            return RefundResult.timeout("PG 환불 응답 본문이 비어 있습니다.");
        }
        return switch (response.status()) {
            case "REFUNDED" -> RefundResult.succeeded(response.providerRefundId(),
                    response.refundedAt() == null ? null : Instant.parse(response.refundedAt()));
            case "FAILED", "DECLINED" -> RefundResult.failed("PG_REFUND_DECLINED", response.failureReason());
            default -> RefundResult.timeout("해석할 수 없는 PG 환불 상태: " + response.status());
        };
    }

    private ConfirmResult interpret(ConfirmApiResponse response) {
        if (response == null || response.status() == null) {
            return ConfirmResult.timeout("PG 응답 본문이 비어 있습니다.");
        }
        return switch (response.status()) {
            case "SUCCEEDED" -> ConfirmResult.succeeded(response.providerPaymentId(),
                    response.approvedAt() == null ? null : Instant.parse(response.approvedAt()));
            case "FAILED", "DECLINED" -> ConfirmResult.failed("PG_DECLINED", response.failureReason());
            default -> ConfirmResult.timeout("해석할 수 없는 PG 상태: " + response.status());
        };
    }

    private record TransactionsApiResponse(Integer count, List<TransactionApiItem> transactions) { }

    private record TransactionApiItem(String providerPaymentId, String merchantPaymentId, String orderId,
                                      Long amount, String status, String processedAt, Long refundedAmount,
                                      String providerRefundId, String refundedAt) { }

    private record ConfirmApiRequest(String merchantPaymentId, String orderId, long amount) { }

    private record RefundApiRequest(String merchantRefundId, long amount) { }

    private record RefundApiResponse(String providerRefundId, String providerPaymentId, String merchantRefundId,
                                     Long amount, String status, String refundedAt, String failureReason) { }

    private record ConfirmApiResponse(String providerPaymentId, String merchantPaymentId, Long amount,
                                      String status, String approvedAt, String failureReason) { }
}
