# Understanding Exchange Engine Performance Through JVM Tuning: A Migration from Rust

## 1. Introduction

### 1.1 Project Background

This project began as a migration of an exchange matching engine from Rust to Java,  
with the goal of **directly validating the performance characteristics and limitations of JVM-based systems**.

Previously, I had primarily worked on NestJS (Node.js) backend development,  
and while I had experience with Java and Spring,  
I had never deeply explored JVM memory management and GC behavior from a performance decision-making perspective.

During recent Java backend recruitment processes,  
I repeatedly encountered questions about JVM tuning and GC,  
and I felt the need to understand, not by memorizing options, but through actual systems,  
**"what JVM handles automatically,  
and where developers need to take responsibility"**.

So I decided to use the core logic of a Rust-based exchange matching engine  
that I had previously developed and was running in production as a baseline,  
implement the same functionality in Java,  
and compare and analyze how JVM tuning affects performance.

---

### 1.2 What is an Exchange Matching Engine?

An exchange matching engine is a core component of the system that receives orders submitted by users,  
matches them according to predetermined priority rules,  
and determines execution results.

**The main functions are as follows:**

- Order reception and validation
- Price-Time Priority based matching  
  (orders submitted first take priority at the same price)
- Trade execution and balance updates
- Order Book management

This component requires both the highest throughput and the lowest latency  
in the entire exchange system.

**The core performance requirements are as follows:**

- **Low latency**: Minimize response delay from order submission to execution
- **High throughput**: Process tens of thousands of orders per second
- **Consistent performance**: Minimize delay variations caused by GC pauses

Due to these characteristics,  
GC behavior, memory management patterns, and object lifecycle  
directly impact the performance of the matching engine.

---

### 1.3 This Article's Perspective and Core Questions

This article does not aim to list JVM tuning options  
or present specific configurations as definitive answers.

Instead, it records the process of answering the following questions:

- What change actually had the greatest impact in JVM tuning?
- When is GC replacement (ZGC) effective, and when does it actually degrade performance?
- Where is the boundary between what JVM handles automatically and what developers must control?
- When measurement results differ from expectations, what decisions should be made?

This article is an experimental record that documents, through the process of  
**hypothesis formation → execution → measurement → validation → rejection or adoption**,  
the actual impact of JVM tuning on system performance.

---

## 2. Project Overview

### 2.1 Exchange Matching Engine Structure

The matching engine used in this project  
has an intentionally simplified structure  
to clearly observe JVM and code-level performance characteristics.

**The main components are as follows:**

- **OrderBook**: TreeMap-based order book  
  (price priority, time priority at the same price)
- **Matcher**: Order matching logic  
  (FIFO-based execution)
- **Executor**: Trade execution and balance updates
- **BalanceCache**: Memory-based balance management

**The order processing flow is as follows:**

```
Order Submission → Balance Lock → OrderBook Registration → Matching Attempt → Trade Execution → Result Return
```

**The main design characteristics are as follows:**

- **Single-threaded synchronous processing**
  - Eliminates multi-threaded contention, lock costs, and scheduling effects
  - To more clearly observe GC and object creation costs
- **Memory-based processing**
  - Excludes DB, WAL, and disk I/O
  - Focuses on pure JVM memory management and GC characteristics
- **Direct call structure**
  - Removes queues, channels, and network layers
  - Performance measurement with minimal unnecessary overhead

This structure is not intended to replicate a production architecture,  
but rather is designed as **an experimental baseline structure  
to observe the effects of JVM tuning and code changes without distortion**.

---

### 2.2 Rust → Java Migration Approach

The Rust version matching engine used as a baseline  
was code I had previously developed and was running in production,  
with performance characteristics and bottlenecks already clearly identified.

**The main characteristics of the Rust implementation are as follows:**

- High processing performance through zero-cost abstraction
- Compile-time memory safety guarantees
- Explicit memory lifecycle management without GC

In migrating to Java,  
I minimized functional expansion or structural changes,  
and made maintaining **the same logic and processing flow as the Rust version**  
the most important principle.

Through this, I aimed to clearly observe the following differences:

- Performance variability introduced by GC
- Object allocation and lifecycle management costs
- The impact of JIT compilation on repeatedly executed code

In other words, this migration was not about  
"creating a better design in Java",  
but rather **an experiment to compare what costs and benefits occur  
when running the same problem on different runtimes**.

---

## 3. Environment and Benchmark Configuration

### 3.1 Hardware Environment

Benchmarks were performed in a personal development environment,  
based on the following hardware specifications:

- CPU: Apple M3 (11-core)
- Memory: 18GB
- OS: macOS (ARM64)

Being an Apple Silicon (ARM64) environment,  
there are differences in native code generation and cache characteristics  
compared to typical x86 server environments.  
Therefore, this document focuses on interpreting results based on  
**relative changes before and after configuration changes within the same environment**,  
rather than absolute performance numbers.

---

### 3.2 Software Environment

The software environment used for benchmarks is as follows:

- JDK: OpenJDK 17.0.11 (Amazon Corretto)
- Spring Boot: 4.0.1
- Build Tool: Gradle
- Benchmark Tool: JMH (Java Microbenchmark Harness) 1.37

Java 17 is a Long-Term Support (LTS) version,  
chosen to minimize differences from actual production environments.

---

### 3.3 Benchmark Design Principles

JMH was used for performance measurement.  
This choice was made to minimize measurement result distortion  
caused by JVM's JIT compilation, inlining, dead code elimination, etc.

The purpose of this benchmark is not to measure maximum performance,  
but rather **to consistently compare performance differences according to configuration changes**.  
To achieve this, the following principles were applied:

- Clearly separate warmup and measurement
- Repeated measurement in a single JVM instance
- Prioritize comparability between configuration changes

Warmup and measurement were each performed 3 times,  
with each iteration running for 2 seconds.  
This is the minimum configuration to  
reproducibly observe performance after JIT optimization has stabilized.

In practice, when the warmup count was increased from 1 to 3,  
the variance in measurement results decreased significantly,  
and performance differences according to JVM option changes became more clearly visible.

---

### 3.4 Measurement Metrics and Test Scenarios

The main measurement metrics are as follows:

- TPS (Transactions Per Second): Number of orders processable per second
- GC Pause Time: Application suspension time caused by GC
- Measurement unit: μs/op (time per operation)

Test scenarios were configured based on the main usage patterns of exchange matching engines:

- limitOrderTps: Limit order processing performance
- marketBuyTps: Market buy order processing performance
- mixedTps: A scenario mixing limit buy/sell and market orders

Order counts were adjusted in units of 1,000 / 5,000 / 10,000 / 50,000  
to observe performance changes and JVM behavior characteristics as load increases.

---

### 3.5 Initial State Configuration

To ensure reproducibility of benchmark results,  
all tests were configured to start from the same initial state:

- Number of users: 100
- Initial balance: SOL 10,000 / USDT 10,000,000
- Seed order book: 1,000,000 SOL sell order at 100.00 USDT

This controlled for insufficient balance or exception situations  
not affecting performance measurement.

---

## 4. JVM and GC: Minimum Background to Understand the Experiment

This chapter does not aim to comprehensively explain JVM internal structure.  
It only covers **what is absolutely necessary** to interpret the tuning results that follow.

The important questions in this project are:

- How does JVM manage object lifecycle?
- How does this management method affect TPS and latency?
- Where is the boundary between what developers can control and what must be left to JVM?

---

### 4.1 JVM's Role: Where the Runtime Intervenes

JVM is not simply a runtime that executes bytecode.  
It **determines at runtime** when objects are created, moved, and destroyed,  
and GC and JIT compiler deeply intervene in this process.

Comparing with Rust makes this difference clear:

- Rust: Object lifecycle is determined at compile time
- JVM: Object survival and movement timing are determined at runtime

In other words, in JVM-based systems,  
even with identical code, **performance characteristics can vary  
depending on memory state and GC behavior at execution time**.

This experiment aims to observe how this "runtime intervention cost"  
manifests in actual TPS and latency.

---

### 4.2 Heap Memory and Object Lifecycle

In JVM, most objects are allocated on the heap.  
With G1GC, the heap is divided into multiple regions,  
and objects move between Young and Old areas according to their survival time.

The important points in this structure are the following two:

- Most objects have very short lifecycles
- Objects that survive longer directly impact GC costs

In the case of exchange matching engines:

- Order objects and intermediate matching objects are created and destroyed very quickly
- Order books and balance information are maintained relatively longer

Therefore, object creation frequency and survival patterns  
directly affect GC frequency and pause time.

In later chapters, the reasons why specific JVM options affected performance  
can mostly be explained by this **object lifecycle distribution change**.

---

### 4.3 GC and Stop-The-World (STW)

GC is a mechanism for reclaiming objects that are no longer used.  
The problem is that in some phases of GC, application threads are suspended (STW).

STW affects performance in the following ways:

- Order processing delays occur
- Instantaneous TPS drops
- Latency variance increases (tail latency worsens)

In systems sensitive to latency like exchange engines,  
**maximum latency and variability** are more important than average performance.

In this experiment, the goal of GC tuning was not  
to "make GC faster",  
but rather **to make performance variations caused by GC predictable**.

---

### 4.4 Why G1GC Was Chosen

This project used G1GC, the default GC for Java 17.

G1GC has the following characteristics:

- Manages heap in region units
- Processes incrementally rather than collecting the entire heap at once
- Behavioral characteristics that aim to meet pause time targets

This is a design to provide **consistent response times**  
in large heap and long-running services.

In this experiment, since the purpose was to confirm:

- What level of performance the default GC configuration provides
- Whether additional tuning actually creates meaningful improvements

G1GC was chosen as the baseline.

---

### 4.5 Non-Heap Memory: The Most Important Area in This Experiment

JVM memory is not composed solely of the heap.  
In practice, problems in production environments  
often occur in Non-Heap areas.

The areas particularly important in this experiment are:

- Metaspace: Class metadata
- Code Cache: Storage for JIT-compiled code
- Direct Memory: Area used by NIO and native memory
- Thread Stack: Memory that increases proportionally with thread count

These areas grow independently of the heap,  
and if not explicitly limited,  
can lead to unexpected OOM in container environments.

The reason why Non-Heap limitation alone improved both performance and stability  
in later chapters is that  
the impact these areas have on JVM behavior  
is greater than expected.

---

### 4.6 Redefining the Purpose of JVM Tuning

The purpose of JVM tuning in this project is not:

- Creating maximum performance
- Presenting specific options as definitive answers

Rather, it is:

- Understanding the causes of performance variations
- Distinguishing controllable and uncontrollable areas
- Securing predictable performance characteristics

Now, in the next chapter,  
we will examine, in experimental order,  
what results actual JVM option changes produced,  
based on this background.

---

## 5. Initial Benchmark Results and Problem Identification

### 5.1 Baseline Measurement

Before applying JVM tuning,  
performance in the default configuration state was measured as a baseline.

The purpose of this stage was not  
to evaluate "how fast it is",  
but rather **to secure a reference point for comparing subsequent changes**.

The JVM configuration used for measurement was:

- Heap: Both Xms / Xmx fixed at 2GB
- GC: G1GC
- No Non-Heap related limitations

Benchmark results for order count 1,000 were:

- limitOrderTps: 2,357 TPS (424.176 μs/op)
- marketBuyTps: 1,649 TPS (606.509 μs/op)
- mixedTps: 1,921 TPS (520.454 μs/op)

GC behavior observation results were:

- Total GC occurrences: 51 times
- Average pause time: 1.42ms
- Maximum pause time: 7.53ms
- Minimum pause time: 0.91ms

Looking at average values alone, it may not seem like a major problem.  
However, given the characteristics of exchange matching engines,  
**the problem lies not in the average but in the worst case**.

---

### 5.2 Interpreting Observation Results

The focus in Baseline measurement results was not TPS itself.  
The following three signals became the starting point for subsequent tuning.

First, the maximum GC pause time reached 7.53ms.  
Although it's a one-time value,  
in systems sensitive to latency like matching engines,  
this level of pause can lead to perceived performance degradation.

Second, there was no explicit limitation on Non-Heap memory.  
While heap usage appeared stable,  
Metaspace, Code Cache, and Direct Memory  
could grow in areas outside JVM (native areas).

This implies the possibility of abnormal termination  
in forms other than Heap OOM  
when memory limits are reached in container environments.

Third, the variance in GC pause time was large.  
The difference between average pause time and maximum pause time  
was interpreted as a signal that performance is not consistent.

In exchange engines, what matters is not  
"mostly fast",  
but rather **"always predictable"**.

---

### 5.3 Problem Definition

Through Baseline measurement, the problem was defined as follows:

- TPS itself is not a direct comparison target with Rust-based implementation,
  and at this point, it was judged not to be an indicator requiring immediate improvement.
- However, latency variability due to GC exists.
- Non-Heap memory is in an uncontrolled state.
- The current configuration has risk factors in terms of **stability and predictability** rather than performance.

Therefore, the goal of the next stage was not  
to dramatically improve performance, but rather:

- To reduce delays caused by GC, or
- At minimum, make delay patterns predictable, and
- Eliminate memory risks in production environments

Based on this problem definition,  
step-by-step JVM tuning begins in the next chapter.

---

## 6. JVM Tuning Process: Decision-Making and Results

This chapter organizes what problems were identified,  
what hypotheses were formed,  
and what decisions were made based on measurement results  
as JVM tuning was actually conducted.

The goal here is not  
to present specific options as definitive answers,  
but rather **to document the reasons and grounds that made decision-making possible**.

---

### 6.1 Decision-Making Process

All tuning processes followed this common flow:

Problem Identification → Hypothesis Formation → Option Evaluation → Execution → Measurement → Analysis → Decision

This process was not a procedure to maximize performance,  
but rather a **criterion used to make the impact of each change interpretable**.

---

### 6.2 Adding Non-Heap Memory Limitations

#### 6.2.1 Problem Identification

Baseline measurement results showed that heap usage itself appeared stable,  
but there was no explicit control over Non-Heap areas.

The observed facts were:

- Metaspace, Code Cache, and Direct Memory grow independently of the heap
- With current configuration, it's difficult to predict total JVM memory usage
- In container environments, processes can terminate in forms other than Heap OOM

The problem was not simply memory shortage,  
but rather that **memory usage patterns were in an uncontrolled state**.

---

#### 6.2.2 Hypothesis Formation

The following hypothesis was formed:

Setting upper limits on Non-Heap memory  
will make memory usage predictable,  
and GC behavior may also show more stable patterns.

The expected effects were:

- Improved predictability of memory usage
- Reduced OOM risk in production environments
- Reduced variance in GC pause time
- TPS remains similar or changes slightly

---

#### 6.2.3 Option Evaluation

The following Non-Heap related options were reviewed:

- MaxMetaspaceSize: Upper limit for class metadata area
- ReservedCodeCacheSize: Upper limit for JIT-compiled code cache
- MaxDirectMemorySize: Limitation on NIO and native memory usage
- Thread Stack Size: Stack memory size per thread

Each value was set not for extreme optimization,  
but at levels that can be used without issues in typical server environments.

---

#### 6.2.4 Execution

Benchmarks were re-run under identical conditions  
with Non-Heap memory limitations added.

---

#### 6.2.5 Measurement Results

Benchmark results for order count 1,000 were:

- limitOrderTps: 2,357 → 2,572 (+9.1%)
- marketBuyTps: 1,649 → 1,843 (+11.8%)
- mixedTps: 1,921 → 2,034 (+5.9%)

GC observation metrics changed as follows:

- Average pause time: 1.42ms → 1.29ms
- Maximum pause time: 7.53ms → 2.54ms
- Minimum pause time: No change

Especially as maximum pause time decreased significantly,  
tail latency showed noticeable improvement.

---

#### 6.2.6 Analysis

This result was more positive than expected.

It's easy to think that Non-Heap limitations sacrifice performance,  
but in reality, it was interpreted that by clearly recognizing the memory range JVM can use,  
GC behavior showed more stable patterns.

Also, by limiting Code Cache,  
preventing JIT compilation from unnecessarily expanding  
was also seen as an indirect performance improvement factor.

---

#### 6.2.7 Decision

It was decided to maintain Non-Heap memory limitations.

- TPS actually improved
- Maximum GC pause time decreased significantly
- Memory stability in production environments could also be secured

At this point, the most important lesson was that  
**memory control does not necessarily lead to performance degradation**.

---

### 6.3 GC Change Attempt: G1GC → ZGC

#### 6.3.1 Problem Identification

Through Non-Heap limitations, maximum pause time decreased significantly,  
but GC-caused delays had not completely disappeared.

Given the characteristics of exchange matching engines,  
it was necessary to review whether there were options to further reduce tail latency,  
and in this process, the option of changing the GC itself was considered.

---

#### 6.3.2 Hypothesis Formation

ZGC is a GC designed for ultra-low latency,  
with maintaining pause time below 1ms as its main goal.

The following hypothesis was formed:

Using ZGC will  
almost eliminate tail latency caused by GC pauses,  
and performance variability will also decrease significantly.

However, it was expected that some throughput loss might need to be accepted.

---

#### 6.3.3 Execution and Measurement

After changing GC to ZGC, benchmarks were performed under identical conditions.

Measurement results were:

- Average and maximum pause times essentially converged to 0
- GC frequency actually increased
- TPS decreased by about 9~10% in some scenarios

---

#### 6.3.4 Analysis

ZGC reduced pause time as dramatically as expected.  
But in return, as concurrent GC work increased,  
CPU resources were continuously consumed.

The current workload has a heap size of 2GB, which is relatively small,  
and has characteristics where objects with short lifecycles are frequently created and destroyed.  
Under these conditions, ZGC's ultra-low latency characteristics  
did not provide enough benefit to offset the throughput loss.

---

#### 6.3.5 Decision

ZGC was judged unsuitable for current conditions  
and rolled back to G1GC.

ZGC is an excellent GC, but  
it was not a universally suitable choice for all workloads and heap sizes.

---

### 6.4 G1GC Parameter Adjustment Experiments and Reconfirmation of Defaults

After concluding that ZGC was unsuitable,  
some parameter adjustments were attempted while maintaining G1GC.

- MaxGCPauseMillis adjustment
- InitiatingHeapOccupancyPercent adjustment

Both experiments showed some changes in pause time,  
but showed unfavorable results in terms of TPS.

Through this, it was confirmed that for the current workload,  
G1GC's default parameters were already the most balanced choice.

---

### 6.5 Final JVM Configuration Decision

The JVM configuration finally chosen was:

- Maintain G1GC
- Fixed heap size
- Apply Non-Heap memory limitations
- Keep GC parameters at defaults

This configuration was not a choice aimed at maximum performance,  
but rather **a combination that provides low latency variance and predictable performance characteristics**.

In the next chapter,  
we examine code-level optimizations performed to reduce GC pressure.

---

## 7. Code Optimization Process: Decision-Making and Results

After controlling GC-caused delay variability to a certain level through JVM tuning,  
whether there was room for additional performance improvement at the code level was reviewed.

The purpose of this stage was not to dramatically increase absolute TPS,  
but rather to **reduce GC pressure and simplify execution paths**  
by removing unnecessary object creation and repeated operations.

---

### 7.1 Optimization Strategy Formation

Code optimization was approached with the following criteria:

1. Identify bottleneck candidates through profiling
2. Check for repeated object creation or calculations
3. Judge whether improvement is possible with minimal code changes
4. Re-measure under identical conditions after changes to verify effectiveness

All optimizations were decided based on  
whether "there is a measurable change".

---

### 7.2 Benchmark Code Optimization

#### 7.2.1 Problem Identification

While analyzing benchmark code,  
it was confirmed that `BigDecimal` objects were being newly created in each iteration  
within the order creation loop.

The pre-optimization code was in the following form:

```java
@Benchmark
public void limitOrderTps(Blackhole bh) {
    for (int idx = 0; idx < orderCount; idx++) {
        BigDecimal price = BASE_PRICE.add(
            PRICE_STEP.multiply(new BigDecimal(idx % 50))
        );
        BigDecimal amount = new BigDecimal("1.0");
        // ...
    }
}
```

This implementation had the following problems:

- New objects were created every time even though the same price pattern repeats
- `BigDecimal` operation and object creation costs accumulated
- Unnecessary heap allocation increased GC pressure

---

#### 7.2.2 Hypothesis Formation

The following hypothesis was formed:

> By pre-calculating repeatedly used price values and caching them in a table,  
> object creation costs can be reduced and GC pressure can be alleviated.

Expected effects were:

- Reduced object creation count
- Reduced Young GC frequency
- Potential for 10~20% level performance improvement in TPS terms

---

#### 7.2.3 Execution

Price tables were pre-generated,  
and code was modified to reference them within the loop.

```java
private static final BigDecimal[] LIMIT_PRICE_TABLE =
    buildPriceTable(50, BASE_PRICE, PRICE_STEP);

@Benchmark
public void limitOrderTps(Blackhole bh) {
    for (int idx = 0; idx < orderCount; idx++) {
        BigDecimal price =
            LIMIT_PRICE_TABLE[idx % LIMIT_PRICE_TABLE.length];
        BigDecimal amount = ORDER_AMOUNT;
        // ...
    }
}
```

The scope of changes was limited to benchmark code,  
and care was taken not to affect actual matching logic or domain behavior.

---

#### 7.2.4 Measurement

After JVM tuning was completed, benchmarks were re-run under identical conditions  
(order count 1,000).

| Benchmark     | Before Optimization | After Optimization | Change Rate |
| ------------- | ------------------- | ------------------ | ----------- |
| limitOrderTps | 2,572               | 2,778              | +8.0%       |
| marketBuyTps  | 1,843               | 1,933              | +4.9%       |
| mixedTps      | 2,034               | 2,137              | +5.1%       |

GC-related metrics were:

| Metric             | Before Optimization | After Optimization |
| ------------------ | ------------------- | ------------------ |
| GC Occurrences     | 51 times            | 49 times           |
| Average pause time | 1.29ms              | 1.30ms             |

---

#### 7.2.5 Analysis

TPS did not reach the expected 10~20%,  
but improvement of about 8% was clearly observed.

The reasons why the effect was more limited than expected were interpreted as follows:

1. **Impact of Warmup**  
   When warmup was insufficient, JIT optimization was not completed,  
   so differences hardly appeared in initial measurements.  
   Only after increasing warmup to 3 iterations did performance differences become stably observable.

2. **Matching Logic Itself is the Main Bottleneck**  
   The `TreeMap`-based matching logic and  
   `BigDecimal` operations themselves took a larger share than object creation costs.

3. **JIT Compiler's Prior Optimizations**  
   Through optimizations like constant folding and inlining,  
   some operations were already offset at the JVM level.

---

#### 7.2.6 Decision

BigDecimal caching optimization was finally decided to be applied.

- TPS clearly improved
- Code change scope was limited
- It aligned with the direction of reducing GC pressure

Through this optimization, the following points were reconfirmed:

- Without sufficient warmup, performance measurement is easily distorted
- Even small optimizations can accumulate into meaningful improvements
- The bottlenecks that determine overall performance are often in algorithms and data structures

---

### 7.3 Reflection on BigDecimal Choice: Trade-off Between Accuracy and Performance

In the early stages of exchange engine implementation,  
using `BigDecimal` for price and quantity representation was naturally chosen.

In financial domains, decimal errors can directly lead to trust issues,  
and accuracy was the top priority.  
Especially since the Rust implementation also used fixed-point based operations,  
`BigDecimal` was judged to be the most semantically corresponding type  
in the Java migration process.

However, while actually conducting benchmarks and tuning in the JVM environment,  
the cost of `BigDecimal` became more clearly recognized:

- Object creation cost is high
- Due to immutable object characteristics, new objects are created with each operation
- Long operation paths limit JIT optimization

These points acted as cumulative costs in high-frequency matching loops.

In this optimization, the type itself was not changed,  
but by reducing object creation frequency through price table caching,  
the cost of `BigDecimal` was partially alleviated.

The lesson learned from this experience is clear:

- At the initial design stage, **prioritizing accuracy was a reasonable choice**
- After performance requirements are concretized, **the trade-off between accuracy and performance must be re-evaluated**

If higher throughput or lower latency is required in actual production environments,  
switching to fixed-point (long-based) representation or domain-specific numeric types  
could also be sufficient candidates for consideration.

What matters is not whether a specific choice was "right or wrong",  
but rather **whether the choice can be re-evaluated as requirements change**.

---

## 8. Final Results and Comprehensive Analysis

### 8.1 Overall Improvement Effects

Performance comparison by stage for order count 1,000:

| Scenario      | Configuration                 | limitOrderTps | Max pause time |
| ------------- | ----------------------------- | ------------- | -------------- |
| 1. Baseline   | No JVM tuning                 | 2,357 TPS     | 7.53ms         |
| 2. JVM tuning | Non-Heap limitation           | 2,572 TPS     | 2.54ms         |
| 3. JVM + Code | Non-Heap limitation + caching | 2,778 TPS     | 2.50ms         |

Final results compared to Baseline:

- TPS improved by approximately **17.9%**
- Maximum GC pause time decreased by **approximately 67%**
- GC pause distribution stabilized and tail latency was significantly alleviated

More than the absolute numerical improvement in TPS itself,  
**securing predictability of latency** was the most important change.

---

### 8.2 Key Insights

#### JVM Tuning Perspective

- Non-Heap memory limitation was not simply a stability option,  
  but a mechanism that made JVM use memory more conservatively and predictably.
- ZGC provided very impressive pause times, but  
  in the current workload (2GB heap, high object creation frequency structure),  
  throughput loss was clearly evident.
- G1GC's default parameters are already sufficiently mature,  
  and adjusting them without clear grounds could actually degrade performance.

#### Code Optimization Perspective

- Removing object creation creates cumulative effects in the direction of reducing GC pressure  
  rather than producing large effects alone.
- The actual bottleneck was in matching algorithms and data structures rather than object creation,  
  and this was not an area that could be solved by JVM tuning alone.
- Without sufficient warmup, the effectiveness of optimization cannot be accurately judged.

#### Decision-Making Perspective

- All tuning was a repetition of hypothesis → execution → measurement → validation.
- Results that differed from expectations (ZGC) were not failures but experiments that clarified judgment criteria.
- Being able to explain "why it doesn't work" became the starting point for the next choice.

---

### 8.3 Lessons Learned from This Project

- Simple configuration changes can lead to significant stability improvements.
- The latest technology is not always the answer to current problems.
- The most dangerous thing in performance tuning is confidence without measurement.
- Latency, throughput, and stability are always in a trade-off relationship.

---

## 9. Conclusion

### 9.1 Project Summary

This project was not simply an experiment to improve Java performance.

- I understood what choices JVM makes in what situations
- I experienced that GC tuning is not "performance improvement" but "changing the nature of performance"
- I updated judgment criteria through actual numbers and failure experiences

### 9.2 Closing

What language you develop in may also be important, but I believe the virtue of a backend developer is  
**being able to explain when and why the system slows down**.

This migration and tuning process  
was an experience that made me view JVM runtime not as a black box  
but as **an understandable system**.

---

### Personal Reflection

Honestly, I felt burdened when starting JVM tuning.  
My main experience was TypeScript-based, and other projects were written in Rust,  
so JVM was not a familiar area for me.

But what I felt while actually conducting tuning was surprisingly simple.  
The approach was not much different from the problem-solving process I already knew.  
Observing memory usage, checking where delays occur,  
forming hypotheses and then validating with measurements—this process was language-agnostic.

The biggest lesson from this experience was not  
"being able to do JVM tuning",  
but rather the conviction that  
**I can solve problems with the same thinking approach in any runtime**.

---
