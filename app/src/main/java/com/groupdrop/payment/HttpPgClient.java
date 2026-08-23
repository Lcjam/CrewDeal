package com.groupdrop.payment;

import com.groupdrop.common.GroupdropProperties;
import java.time.Instant;
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

    private record ConfirmApiRequest(String merchantPaymentId, String orderId, long amount) { }

    private record RefundApiRequest(String merchantRefundId, long amount) { }

    private record RefundApiResponse(String providerRefundId, String providerPaymentId, String merchantRefundId,
                                     Long amount, String status, String refundedAt, String failureReason) { }

    private record ConfirmApiResponse(String providerPaymentId, String merchantPaymentId, Long amount,
                                      String status, String approvedAt, String failureReason) { }
}
