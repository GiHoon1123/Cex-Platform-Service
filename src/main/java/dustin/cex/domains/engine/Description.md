해당 도메인(engine)은 JVM 성능 측정 및 튜닝을 위해 Rust로 구현된 거래소 엔진의 일부를 Java로 마이그레이션한 코드입니다.

## 목적

- JVM 벤치마크 및 성능 측정 (JMH 사용)
- Rust 엔진과의 성능 비교
- GC 튜닝 및 최적화 실험

## 특징

- 실제 서비스에서 사용하는 메인 기능이 아닙니다
- 벤치마크 및 성능 분석용으로만 사용됩니다
- 실제 엔진 운영은 Rust 엔진(Cex-Engine)이 담당합니다

This domain is a code that migrated some of the Exchange Engine implemented in Rust to Java for JVM performance measurement and tuning.

## Purpose

- JVM benchmarks and performance measurements (using JMH)
- Performance Comparison with Rust Engine
- GC Tuning and Optimization Experiments

## Characteristics

- This is not the main function used by the actual service
- Used for benchmarking and performance analysis only
- The actual operation is handled by the Rust engine (Cex-Engine)
