-- 입금 확정 취소 때 그 확정이 만든·바꾼 별칭만 되돌리기 위한 기록 (2026-10-13 코드 리뷰):
-- 전에는 "입금자명 + 이 주문의 고객"과 같은 별칭이면 무조건 지워서, 다른 공구에서 먼저 생긴 별칭까지 지워졌다.
-- paid_alias_change: NULL(건드리지 않음) / CREATED(새로 만듦) / CHANGED(다른 고객 → 이 고객으로 바꿈)
-- paid_alias_previous_key: CHANGED 일 때 바꾸기 전 고객 키
ALTER TABLE customer_order ADD COLUMN paid_alias_change VARCHAR(10);
ALTER TABLE customer_order ADD COLUMN paid_alias_previous_key VARCHAR(100);
