#!/bin/bash

# 봇 문제 해결을 위한 재시작 및 테스트 스크립트
# 주의: 프로덕션 환경에서는 사용하지 마세요!

set -e

echo "=========================================="
echo "봇 문제 해결: 재시작 및 테스트"
echo "=========================================="
echo ""

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 설정
KAFKA_BOOTSTRAP_SERVER="${KAFKA_BOOTSTRAP_SERVER:-localhost:9092}"
KAFKA_TOPIC="order-events"
CONSUMER_GROUP="cex-consumer-group"

# 확인 메시지
echo -e "${YELLOW}주의: 이 스크립트는 다음 작업을 수행합니다:${NC}"
echo "1. 카프카 Consumer Group 리셋 (최신 offset으로)"
echo "2. 엔진 재시작 안내"
echo "3. Java 애플리케이션 재시작 안내"
echo ""
read -p "계속하시겠습니까? (y/N): " -n 1 -r
echo ""
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "취소되었습니다."
    exit 1
fi

echo ""
echo "=========================================="
echo "1. 카프카 Consumer Group 상태 확인"
echo "=========================================="

if command -v kafka-consumer-groups &> /dev/null; then
    echo "Consumer Group 상태:"
    kafka-consumer-groups --bootstrap-server $KAFKA_BOOTSTRAP_SERVER \
        --group $CONSUMER_GROUP \
        --describe || echo "Consumer Group이 없거나 접근할 수 없습니다."
else
    echo -e "${YELLOW}kafka-consumer-groups 명령을 찾을 수 없습니다.${NC}"
    echo "수동으로 확인하세요:"
    echo "kafka-consumer-groups --bootstrap-server $KAFKA_BOOTSTRAP_SERVER --group $CONSUMER_GROUP --describe"
fi

echo ""
echo "=========================================="
echo "2. 카프카 Consumer Group 리셋"
echo "=========================================="

read -p "Consumer Group을 최신 offset으로 리셋하시겠습니까? (y/N): " -n 1 -r
echo ""
if [[ $REPLY =~ ^[Yy]$ ]]; then
    if command -v kafka-consumer-groups &> /dev/null; then
        echo "Consumer Group 리셋 중..."
        kafka-consumer-groups --bootstrap-server $KAFKA_BOOTSTRAP_SERVER \
            --group $CONSUMER_GROUP \
            --reset-offsets \
            --to-latest \
            --topic $KAFKA_TOPIC \
            --execute
        
        if [ $? -eq 0 ]; then
            echo -e "${GREEN}Consumer Group 리셋 완료${NC}"
        else
            echo -e "${RED}Consumer Group 리셋 실패${NC}"
        fi
    else
        echo -e "${YELLOW}kafka-consumer-groups 명령을 찾을 수 없습니다.${NC}"
        echo "수동으로 실행하세요:"
        echo "kafka-consumer-groups --bootstrap-server $KAFKA_BOOTSTRAP_SERVER --group $CONSUMER_GROUP --reset-offsets --to-latest --topic $KAFKA_TOPIC --execute"
    fi
else
    echo "Consumer Group 리셋을 건너뜁니다."
fi

echo ""
echo "=========================================="
echo "3. 데이터베이스 확인"
echo "=========================================="

read -p "최근 봇 주문을 확인하시겠습니까? (y/N): " -n 1 -r
echo ""
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo "다음 SQL을 실행하세요:"
    echo ""
    echo "SELECT id, user_id, order_type, order_side, status, created_at"
    echo "FROM orders"
    echo "WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'bot%@bot.com')"
    echo "ORDER BY id DESC"
    echo "LIMIT 20;"
    echo ""
    echo "최근 trades 확인:"
    echo "SELECT * FROM trades ORDER BY id DESC LIMIT 10;"
fi

echo ""
echo "=========================================="
echo "4. 재시작 안내"
echo "=========================================="

echo -e "${YELLOW}다음 단계를 수동으로 수행하세요:${NC}"
echo ""
echo "1. 엔진 재시작:"
echo "   - 엔진 프로세스 종료"
echo "   - 엔진 재시작"
echo ""
echo "2. Java 애플리케이션 재시작:"
echo "   - Java 애플리케이션 종료"
echo "   - Java 애플리케이션 재시작"
echo "   - 봇이 자동으로 초기화됩니다 (BotManagerService.initializeBots)"
echo ""

read -p "엔진과 Java 애플리케이션을 재시작한 후 Enter를 누르세요..."

echo ""
echo "=========================================="
echo "5. 테스트"
echo "=========================================="

echo "테스트를 시작합니다..."
echo ""

# 봇 주문 생성 테스트 (선택사항)
read -p "봇 주문 생성 테스트를 수행하시겠습니까? (y/N): " -n 1 -r
echo ""
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo "봇 주문 생성 API 호출 예시:"
    echo ""
    echo "curl -X POST http://localhost:8080/api/bot/orders/1/limit/buy \\"
    echo "  -H \"Content-Type: application/json\" \\"
    echo "  -d '{"
    echo "    \"baseMint\": \"SOL\","
    echo "    \"price\": 100.0,"
    echo "    \"amount\": 1.0"
    echo "  }'"
    echo ""
    echo "또는 OrderbookSyncService가 자동으로 주문을 생성합니다."
fi

echo ""
echo "=========================================="
echo "6. 로그 확인"
echo "=========================================="

echo "다음 로그를 확인하세요:"
echo ""
echo "1. 엔진 로그:"
echo "   - [Engine Thread] OrderCommand::SubmitOrder 수신"
echo "   - [Engine Thread] trade_executed 이벤트 발행 시도"
echo "   - [Engine Thread] trade_executed 이벤트 발행 성공"
echo ""
echo "2. Java 애플리케이션 로그:"
echo "   - [Kafka] 체결 이벤트 수신"
echo "   - [Kafka] 체결 이벤트 처리 완료"
echo "   - [Kafka] 주문이 존재하지 않아 체결 처리 스킵 (문제가 있는 경우)"
echo ""

echo "=========================================="
echo "완료!"
echo "=========================================="
echo ""
echo "추가 확인 사항:"
echo "1. 카프카 메시지 확인:"
echo "   kafka-console-consumer --bootstrap-server $KAFKA_BOOTSTRAP_SERVER --topic $KAFKA_TOPIC --from-beginning --max-messages 5"
echo ""
echo "2. trades 테이블 확인:"
echo "   SELECT * FROM trades ORDER BY id DESC LIMIT 10;"
echo ""
