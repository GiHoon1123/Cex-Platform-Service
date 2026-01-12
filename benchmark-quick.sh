#!/bin/bash
# 빠른 벤치마크 테스트 (orderCount=1000만, GC 로그 포함)

echo "=== 빠른 벤치마크 테스트 (orderCount=1000만) ==="
echo "GC 로그: build/results/jmh/gc.log"
echo ""

./gradlew jmh \
  -PjmhArgs="-prof gc -p orderCount=1000" \
  -Dfile.encoding=UTF-8 \
  2>&1 | tee build/results/jmh/quick-benchmark.log

echo ""
echo "=== 완료 ==="
echo "결과: build/results/jmh/quick-benchmark.log"
echo "GC 로그: build/results/jmh/gc.log"
