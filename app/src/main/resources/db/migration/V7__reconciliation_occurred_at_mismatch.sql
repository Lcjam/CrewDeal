-- REC-01: 승인된 거래 발생 시각 비교 정책(D-031)을 별도 불일치 유형으로 보존한다.
ALTER TABLE reconciliation_discrepancies
    DROP CONSTRAINT reconciliation_discrepancies_discrepancy_type_check;

ALTER TABLE reconciliation_discrepancies
    ADD CONSTRAINT reconciliation_discrepancies_discrepancy_type_check
    CHECK (discrepancy_type IN
           ('MISSING_INTERNAL', 'MISSING_PROVIDER', 'AMOUNT_MISMATCH', 'STATUS_MISMATCH',
            'REFUND_MISMATCH', 'OCCURRED_AT_MISMATCH', 'DUPLICATE_PAYMENT', 'UNRESOLVED_INTERNAL'));
