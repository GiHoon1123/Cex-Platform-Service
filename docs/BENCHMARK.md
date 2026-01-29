# 엔진 도메인 벤치마크

## 실행 방법

프로젝트 루트에서 기본 실행:

```bash
./gradlew jmh --no-daemon
```

대상: `src/jmh/java/dustin/cex/benchmarks/` (TpsBenchmark, TpsBenchmarkOptimized)

---

## JVM 옵션 주고 벤치마크 돌리기

옵션은 `-D...` 로 넘기고, 그 다음에 `jmh` 태스크가 같은 JVM 옵션으로 벤치를 돌린다.

---

**최적 결과 나왔던 설정 (Non-Heap 메모리 제한 적용)**

- Metaspace 256MB
- Code Cache 128MB
- Direct Memory 64MB
- Thread Stack 512KB

이 설정이 build.gradle 기본값이라, 아래 명령어가 곧 **최적 옵션으로 돌리는 명령어**다.

```bash
./gradlew jmh --no-daemon
```

**ZGC로 돌리기**

```bash
./gradlew jmh --no-daemon -DgcArg=-XX:+UseZGC
```

**Non-Heap 제한 끄고 돌리기**

```bash
./gradlew jmh --no-daemon -DenableNonHeapLimit=false
```

**특정 벤치만 + JVM 옵션**

```bash
./gradlew jmh --no-daemon -Pjmh.includes='.*TpsBenchmark.*limitOrderTps.*' -DgcArg=-XX:+UseG1GC
```

**추가 JVM 인자 (쉼표 구분)**

```bash
./gradlew jmh --no-daemon -DextraJvmArgs="-XX:MaxGCPauseMillis=10"
```

---

## JVM 옵션 (build.gradle 기본값)

| 구분          | 옵션                                                                     |
| ------------- | ------------------------------------------------------------------------ |
| Heap          | `-Xmx2G`, `-Xms2G`                                                       |
| GC            | `-XX:+UseG1GC`                                                           |
| Metaspace     | `-XX:MaxMetaspaceSize=256m`                                              |
| Code Cache    | `-XX:ReservedCodeCacheSize=128m`                                         |
| Direct Memory | `-XX:MaxDirectMemorySize=64m`                                            |
| Thread Stack  | `-Xss512k`                                                               |
| GC 로그       | `-Xlog:gc*:file=build/results/jmh/gc.log:time,uptime,level,tags`         |
| OOM 시        | `-XX:+HeapDumpOnOutOfMemoryError`, `-XX:HeapDumpPath=build/results/jmh/` |

Non-Heap 제한 끄기: `-DenableNonHeapLimit=false`  
GC 변경(예: ZGC): `-DgcArg=-XX:+UseZGC`  
추가 JVM 인자: `-DextraJvmArgs="-Dfoo=bar"` (쉼표로 구분)

---

## JMH 웜업·측정

**기본값 (build.gradle)**

| 항목        | 값                                    |
| ----------- | ------------------------------------- |
| Warmup      | 3회, 회당 2초                         |
| Measurement | 3회, 회당 2초                         |
| Fork        | 1                                     |
| Mode        | AverageTime (μs/op)                   |
| Param       | orderCount = 1000, 5000, 10000, 50000 |

**웜업/측정 바꿔서 돌리기**

`-Pjmh.속성=값` 으로 오버라이드한다. 그 다음 `jmh` 태스크가 이 설정으로 실행된다.

```bash
# 웜업 5회, 회당 3초 / 측정 5회, 회당 3초
./gradlew jmh --no-daemon -Pjmh.warmupIterations=5 -Pjmh.warmup=3s -Pjmh.iterations=5 -Pjmh.timeOnIteration=3s
```

| 속성                   | 의미            | 예시 |
| ---------------------- | --------------- | ---- |
| `jmh.warmupIterations` | 웜업 반복 횟수  | `5`  |
| `jmh.warmup`           | 웜업 1회당 시간 | `3s` |
| `jmh.iterations`       | 측정 반복 횟수  | `5`  |
| `jmh.timeOnIteration`  | 측정 1회당 시간 | `3s` |

---

## 특정 벤치마크만 실행

```bash
./gradlew jmh --no-daemon -Pjmh.includes='.*TpsBenchmark.*limitOrderTps.*'
```

- `.*TpsBenchmark.*` → 해당 클래스만
- `.*limitOrderTps.*` → 해당 메서드만
- `.*orderCount=1000.*` → 해당 파라미터만

---

## 결과·로그

- JMH 결과: 터미널 출력 + `build/results/jmh/` (플러그인 설정에 따라)
- GC 로그: `build/results/jmh/gc.log`
- OOM 시 힙 덤프: `build/results/jmh/`
