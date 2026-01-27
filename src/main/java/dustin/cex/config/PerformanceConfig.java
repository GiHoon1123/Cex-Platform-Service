package dustin.cex.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 성능 최적화 설정
 * Performance Configuration
 * 대량 데이터 정산 작업을 위한 비동기 처리 및 스레드 풀 설정
 */
@Configuration
@EnableAsync
public class PerformanceConfig {
    
    /**
     * 정산 작업용 비동기 스레드 풀
     * Settlement batch processing thread pool
     */
    @Bean(name = "settlementExecutor")
    public Executor settlementExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // CPU 코어 수에 맞춰 스레드 수 설정
        int corePoolSize = Runtime.getRuntime().availableProcessors();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(corePoolSize * 2);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("settlement-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
    
    /**
     * 일반 비동기 작업용 스레드 풀
     * General async task executor
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("async-");
        executor.initialize();
        return executor;
    }
}
