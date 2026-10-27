-- 플랫폼 원본과 엔진 접수 증명을 같은 새 환경에 준비한다. 접수는 체결 결과가 아니다.
CREATE TABLE engine_order_receipts (
    order_id BIGINT PRIMARY KEY REFERENCES order_submissions(order_id) ON DELETE RESTRICT,
    user_id BIGINT NOT NULL CHECK (user_id > 0),
    order_type TEXT NOT NULL CHECK (order_type IN ('buy', 'sell')),
    order_side TEXT NOT NULL CHECK (order_side IN ('limit', 'market')),
    base_mint TEXT NOT NULL,
    quote_mint TEXT NOT NULL,
    price NUMERIC(30,9),
    amount NUMERIC(30,9),
    quote_amount NUMERIC(30,9),
    fee_rate NUMERIC(10,6) NOT NULL CHECK (fee_rate >= 0 AND fee_rate < 1),
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (order_id > 0)
);
