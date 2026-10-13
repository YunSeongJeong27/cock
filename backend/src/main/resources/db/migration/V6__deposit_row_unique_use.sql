-- 입금 1건은 주문 1건에만 확정된다 (정리본 6-2). 동시에 확정을 두 번 눌러도 DB 가 막도록 유일 제약을 건다 (2026-10-14 점검).
-- NULL(아직 확정 안 됨)은 여러 줄이어도 된다 (H2·PostgreSQL 모두 유일 인덱스에서 NULL 은 서로 다른 값).
CREATE UNIQUE INDEX uq_deposit_row_used_order ON deposit_row (used_order_id);
