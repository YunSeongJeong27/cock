-- 기기 확인 수준 (2026-10-13 점검):
-- 주문을 직접 넣은 기기(TRUE)만 이전 입력값(휴대폰 전체·주소·동호수)을 자동으로 채운다.
-- "입금자명 + 휴대폰 뒷4자리"로만 확인한 기기(FALSE)에는 주문 상태만 보여주고 개인정보는 채우지 않는다.
ALTER TABLE customer_device ADD COLUMN verified_by_order BOOLEAN NOT NULL DEFAULT FALSE;
