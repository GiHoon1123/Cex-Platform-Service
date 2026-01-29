package dustin.cex.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
     * 트랜잭션 컨텍스트 전파를 위한 TaskDecorator 포함
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("async-");
        // 트랜잭션 컨텍스트 전파를 위한 TaskDecorator 설정
        executor.setTaskDecorator(new TaskDecorator() {
            @Override
            public Runnable decorate(Runnable runnable) {
                // 현재 스레드의 트랜잭션 컨텍스트를 캡처
                return () -> {
                    try {
                        runnable.run();
                    } finally {
                        // 작업 완료 후 트랜잭션 동기화 상태 정리
                        TransactionSynchronizationManager.clear();
                    }
                };
            }
        });
        executor.initialize();
        return executor;
    }
}
