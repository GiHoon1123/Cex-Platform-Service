#!/bin/bash

# trades 테이블 확인 스크립트
# PostgreSQL 연결 정보
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-cex}"
DB_USER="${DB_USER:-root}"
DB_PASSWORD="${DB_PASSWORD:-1234}"

echo "=========================================="
echo "trades 테이블 확인"
echo "=========================================="
echo ""

# PostgreSQL 클라이언트가 있는지 확인
if command -v psql &> /dev/null; then
    export PGPASSWORD="$DB_PASSWORD"
    
    echo "1. 최근 trades 확인:"
    psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -c "
    SELECT id, buy_order_id, sell_order_id, buyer_id, seller_id, price, amount, created_at 
    FROM trades 
    ORDER BY id DESC 
    LIMIT 10;
    " 2>&1
    
    echo ""
    echo "2. trades 총 개수:"
    psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -c "
    SELECT COUNT(*) as total_trades FROM trades;
    " 2>&1
    
    echo ""
    echo "3. 최근 봇 주문 확인:"
    psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -c "
    SELECT id, user_id, order_type, order_side, status, price, amount, created_at 
    FROM orders 
    WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'bot%@bot.com')
    ORDER BY id DESC 
    LIMIT 10;
    " 2>&1
    
    echo ""
    echo "4. 체결되지 않은 봇 주문:"
    psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -c "
    SELECT id, user_id, order_type, order_side, status, price, amount, filled_amount, created_at 
    FROM orders 
    WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'bot%@bot.com')
    AND status IN ('pending', 'partial')
    ORDER BY id DESC 
    LIMIT 10;
    " 2>&1
    
    unset PGPASSWORD
else
    echo "psql이 설치되어 있지 않습니다."
    echo "다음 SQL을 직접 실행하세요:"
    echo ""
    echo "SELECT id, buy_order_id, sell_order_id, buyer_id, seller_id, price, amount, created_at"
    echo "FROM trades"
    echo "ORDER BY id DESC"
    echo "LIMIT 10;"
fi
