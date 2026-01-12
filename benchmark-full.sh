#!/bin/bash
# 전체 벤치마크 실행 (모든 orderCount, GC 로그 포함)

echo "=== 전체 벤치마크 실행 (약 6분 소요) ==="
echo "GC 로그: build/results/jmh/gc.log"
echo ""

./gradlew jmh \
  -PjmhArgs="-prof gc" \
  -Dfile.encoding=UTF-8 \
  2>&1 | tee build/results/jmh/full-benchmark.log

echo ""
echo "=== 완료 ==="
echo "결과: build/results/jmh/full-benchmark.log"
echo "GC 로그: build/results/jmh/gc.log"
