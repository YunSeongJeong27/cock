-- 로컬(맥북) 전용 샘플 데이터. application-local.yml 에서만 이 폴더(db/local)를 읽는다. 운영 DB 에는 실행하지 않는다.
-- 데모 판매자: demo@depositcock.local / demo1234
-- 고객 주문서 링크: http://localhost:8080/g/demo-paprika (프론트 개발 서버면 http://localhost:5173/g/demo-paprika)

INSERT INTO seller (email, password_hash, display_name, role, plan, trial_ends_at, suspended,
                    default_bank_name, default_account_number, default_account_holder, created_at)
VALUES ('demo@depositcock.local', '$2a$10$h/xuNp.ucRhLGho7oisjGuhm1AMSHuTIui2pK6SC6kb5JbDWS9yUy', '공구하는 데모', 'SELLER',
        'TRIAL', TIMESTAMP '2099-12-31 23:59:59', FALSE, '토스뱅크', '1000-0000-0000', '데모', CURRENT_TIMESTAMP);

INSERT INTO group_buy (seller_id, title, description, public_token, status, close_at, bank_name, account_number,
                       account_holder, pickup_enabled, pickup_prepay_enabled, pickup_onsite_enabled, delivery_enabled,
                       shipping_fee_type, shipping_fee, nickname_field_enabled, unit_address_field_enabled,
                       kakao_inquiry_url, admin_names, created_at, updated_at)
SELECT id, '파프리카 한봉지 (8일 목요일 오후 픽업)', '선착순 한정수량! 8일 목요일 오후 픽업하세요.', 'demo-paprika', 'OPEN', NULL,
       '토스뱅크', '1000-0000-0000', '데모', TRUE, TRUE, TRUE, TRUE, 'FIXED', 4000, TRUE, FALSE, NULL, '공구장',
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM seller WHERE email = 'demo@depositcock.local';

INSERT INTO product (group_buy_id, name, unit_price, stock_limit, sold, per_order_limit, sort_order, version)
SELECT id, '파프리카 한봉지', 1900, 50, 3, 5, 1, 0 FROM group_buy WHERE public_token = 'demo-paprika';
INSERT INTO product (group_buy_id, name, unit_price, stock_limit, sold, per_order_limit, sort_order, version)
SELECT id, '한우 1++ 차돌 200g', 8900, NULL, 2, NULL, 2, 0 FROM group_buy WHERE public_token = 'demo-paprika';

INSERT INTO pickup_point (group_buy_id, name, info, sort_order)
SELECT id, '캐파점', '오후 1시~7시', 1 FROM group_buy WHERE public_token = 'demo-paprika';
INSERT INTO pickup_point (group_buy_id, name, info, sort_order)
SELECT id, '고촌1호점', '오후 2시~6시', 2 FROM group_buy WHERE public_token = 'demo-paprika';

-- 주문 3건 (입금 대기 2, 현장결제 1)
INSERT INTO customer_order (group_buy_id, order_no, source, customer_key, phone, depositor_name, nickname, receive_method,
                            pickup_point_id, payment_method, items_amount, shipping_fee, expected_amount, status,
                            lookup_token, ordered_at, updated_at)
SELECT g.id, 1, 'FORM', '01011112222', '01011112222', '김지영', '꼭지맘 217-1505', 'PICKUP',
       (SELECT p.id FROM pickup_point p WHERE p.group_buy_id = g.id AND p.sort_order = 1), 'PREPAID', 3800, 0, 3800,
       'ORDERED', 'demo-order-1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM group_buy g WHERE g.public_token = 'demo-paprika';
INSERT INTO customer_order (group_buy_id, order_no, source, customer_key, phone, depositor_name, nickname, receive_method,
                            pickup_point_id, payment_method, items_amount, shipping_fee, expected_amount, status,
                            lookup_token, ordered_at, updated_at)
SELECT g.id, 2, 'FORM', '01033334444', '01033334444', '박서준', '망고맘', 'PICKUP',
       (SELECT p.id FROM pickup_point p WHERE p.group_buy_id = g.id AND p.sort_order = 2), 'PREPAID', 17800, 0, 17800,
       'ORDERED', 'demo-order-2', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM group_buy g WHERE g.public_token = 'demo-paprika';
INSERT INTO customer_order (group_buy_id, order_no, source, customer_key, phone, depositor_name, nickname, receive_method,
                            pickup_point_id, payment_method, items_amount, shipping_fee, expected_amount, status,
                            lookup_token, ordered_at, updated_at)
SELECT g.id, 3, 'FORM', '01055556666', '01055556666', '이수진', '신지', 'PICKUP',
       (SELECT p.id FROM pickup_point p WHERE p.group_buy_id = g.id AND p.sort_order = 1), 'ONSITE', 1900, 0, 1900,
       'ORDERED', 'demo-order-3', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM group_buy g WHERE g.public_token = 'demo-paprika';

INSERT INTO order_item (order_id, product_id, product_name_snapshot, quantity, unit_price_snapshot)
SELECT o.id, p.id, p.name, 2, p.unit_price FROM customer_order o JOIN product p ON p.group_buy_id = o.group_buy_id
WHERE o.lookup_token = 'demo-order-1' AND p.sort_order = 1;
INSERT INTO order_item (order_id, product_id, product_name_snapshot, quantity, unit_price_snapshot)
SELECT o.id, p.id, p.name, 2, p.unit_price FROM customer_order o JOIN product p ON p.group_buy_id = o.group_buy_id
WHERE o.lookup_token = 'demo-order-2' AND p.sort_order = 2;
INSERT INTO order_item (order_id, product_id, product_name_snapshot, quantity, unit_price_snapshot)
SELECT o.id, p.id, p.name, 1, p.unit_price FROM customer_order o JOIN product p ON p.group_buy_id = o.group_buy_id
WHERE o.lookup_token = 'demo-order-3' AND p.sort_order = 1;
