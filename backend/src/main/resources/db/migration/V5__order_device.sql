-- 고객 기기 확인 방식 변경 (2026-10-14 보안 점검):
-- 전에는 남의 휴대폰 번호로 주문만 넣어도 그 번호의 주문 목록이 보이고, 그 기기에 그 사람의 주소·연락처가 자동으로 채워질 수 있었다.
-- 이제 기기는 (1) 자기가 넣은 주문(customer_order.device_token)과 (2) 본인 확인(입금자명 + 휴대폰)을 거친 고객의 주문만 본다.
-- 개인정보(휴대폰 전체·동호수·배송지) 자동 채움은 그 기기에서 넣은 주문에서만 가져온다.
ALTER TABLE customer_order ADD COLUMN device_token VARCHAR(64);
CREATE INDEX idx_order_device ON customer_order (group_buy_id, device_token);
ALTER TABLE customer_device DROP COLUMN verified_by_order;
ALTER TABLE customer_device ADD COLUMN identified BOOLEAN NOT NULL DEFAULT FALSE;
