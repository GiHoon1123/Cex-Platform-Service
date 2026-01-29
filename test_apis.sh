#!/bin/bash

# 조회 API 테스트 스크립트
# Usage: ./test_apis.sh [JWT_TOKEN]

JWT_TOKEN=${1:-""}
BASE_URL="http://localhost:8080"

echo "=========================================="
echo "조회 API 테스트 시작"
echo "=========================================="
echo ""

# 1. 헬스체크
echo "1. 헬스체크 테스트..."
curl -s "$BASE_URL/api/health" | jq '.' || echo "헬스체크 실패"
echo ""
echo "---"
echo ""

# 2. 오더북 조회 (인증 불필요)
echo "2. 오더북 조회 테스트..."
curl -s "$BASE_URL/api/cex/orderbook?baseMint=SOL&quoteMint=USDT&depth=5" | jq '.' || echo "오더북 조회 실패"
echo ""
echo "---"
echo ""

# 3. 체결 내역 조회 (인증 불필요)
echo "3. 체결 내역 조회 테스트..."
curl -s "$BASE_URL/api/cex/trades?baseMint=SOL&quoteMint=USDT&limit=5" | jq '.' || echo "체결 내역 조회 실패"
echo ""
echo "---"
echo ""

# 인증이 필요한 API들 (JWT 토큰이 있는 경우만)
if [ -n "$JWT_TOKEN" ]; then
    AUTH_HEADER="Authorization: Bearer $JWT_TOKEN"
    
    # 4. 내 주문 목록 조회
    echo "4. 내 주문 목록 조회 테스트..."
    curl -s -H "$AUTH_HEADER" "$BASE_URL/api/cex/orders/my?limit=5" | jq '.' || echo "주문 목록 조회 실패"
    echo ""
    echo "---"
    echo ""
    
    # 5. 내 체결 내역 조회
    echo "5. 내 체결 내역 조회 테스트..."
    curl -s -H "$AUTH_HEADER" "$BASE_URL/api/cex/trades/my?limit=5" | jq '.' || echo "내 체결 내역 조회 실패"
    echo ""
    echo "---"
    echo ""
    
    # 6. 모든 포지션 조회
    echo "6. 모든 포지션 조회 테스트..."
    curl -s -H "$AUTH_HEADER" "$BASE_URL/api/cex/positions" | jq '.' || echo "포지션 조회 실패"
    echo ""
    echo "---"
    echo ""
    
    # 7. 모든 잔고 조회
    echo "7. 모든 잔고 조회 테스트..."
    curl -s -H "$AUTH_HEADER" "$BASE_URL/api/cex/balances" | jq '.' || echo "잔고 조회 실패"
    echo ""
    echo "---"
    echo ""
else
    echo "JWT 토큰이 없어 인증이 필요한 API는 테스트하지 않습니다."
    echo "사용법: ./test_apis.sh YOUR_JWT_TOKEN"
fi

echo ""
echo "=========================================="
echo "테스트 완료"
echo "=========================================="
