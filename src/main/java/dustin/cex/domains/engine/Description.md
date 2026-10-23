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

## 현재 벤치마크 설정 · 2026-10-23

출처: [build.gradle](../../../../../../../build.gradle#L105). 아래는 설정 일부이며 독립 실행 예제가 아니다.

```groovy
jmh {
	def gcArg = System.getProperty('gcArg', '-XX:+UseG1GC')
	def extraJvmArgs = System.getProperty('extraJvmArgs')
	def extraList = extraJvmArgs?.split(',')?.collect { it.trim() }?.findAll { it }
	def enableNonHeapLimit = System.getProperty('enableNonHeapLimit', 'true') == 'true'

	// 웜업/측정: -Pjmh.warmupIterations=5 -Pjmh.warmup=3s -Pjmh.iterations=5 -Pjmh.timeOnIteration=3s 로 오버라이드 가능
	warmupIterations = (findProperty('jmh.warmupIterations') ?: 3) as int
	warmup = findProperty('jmh.warmup') ?: '2s'
	iterations = (findProperty('jmh.iterations') ?: 3) as int
	timeOnIteration = findProperty('jmh.timeOnIteration') ?: '2s'

```

`jmh` 블록은 실제 측정의 반복·예열·출력 조건을 정한다. Gradle 설정은 TypeScript 객체처럼 보이지만 플러그인의 설정 메서드를 호출한다. 이 Java 실험은 실제 Rust 체결 코어와 다른 실행 경로다. [현재 런타임과 검증 범위](../../../../../../../docs/decisions/013-supported-runtime-and-dependency-audit.md)에서 컴파일과 실제 측정을 구분한다. 이번 문서 보강에서는 신규 제품 테스트 0개이며 성능 측정·제품 테스트는 실행하지 않았다.
