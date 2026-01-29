package dustin.cex.shared.kafka;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Kafka 설정
 * Kafka Configuration
 * 
 * 역할:
 * - Kafka Producer 설정
 * - Kafka Consumer 설정
 * - KafkaTemplate 빈 등록
 * - KafkaListenerContainerFactory 빈 등록
 */
@Configuration
public class KafkaConfig {
    
    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;
    
    @Value("${spring.kafka.consumer.group-id:cex-consumer-group}")
    private String groupId;
    
    @Value("${spring.kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;
    
    /**
     * Kafka Producer Factory 생성
     * Create Kafka Producer Factory
     */
    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        
        // 성능 최적화 설정
        configProps.put(ProducerConfig.ACKS_CONFIG, "1"); // 리더만 확인 (성능 우선)
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3); // 재시도 횟수
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384); // 배치 크기
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, 10); // 배치 대기 시간 (ms)
        configProps.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432); // 버퍼 메모리 (32MB)
        
        return new DefaultKafkaProducerFactory<>(configProps);
    }
    
    /**
     * KafkaTemplate 빈 등록
     * Register KafkaTemplate Bean
     */
    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
    
    /**
     * Kafka Consumer Factory 생성
     * Create Kafka Consumer Factory
     */
    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        
        // Consumer 성능 최적화 설정
        configProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500); // 한 번에 가져올 최대 레코드 수
        configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); // 수동 커밋 (트랜잭션 처리)
        
        return new DefaultKafkaConsumerFactory<>(configProps);
    }
    
    /**
     * Kafka Listener Container Factory 생성
     * Create Kafka Listener Container Factory
     * 
     * 가상 스레드 사용: 파티션 하나당 하나의 가상 스레드가 담당
     * Virtual Thread: One virtual thread per partition
     * 
     * 동작 방식:
     * 1. setConcurrency(12): 12개의 KafkaMessageListenerContainer 생성
     * 2. 각 컨테이너는 하나의 Kafka Consumer를 가지고 있음
     * 3. 각 Consumer는 하나의 파티션을 담당 (12개 파티션 / 12개 컨테이너 = 파티션당 1개)
     * 4. SimpleAsyncTaskExecutor with virtual threads: 각 메시지 처리를 가상 스레드로 실행
     * 
     * @KafkaListener 어노테이션이 사용하는 팩토리
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = 
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        
        // 동시성 설정: 파티션 수만큼 설정 (파티션 하나당 하나의 컨테이너/Consumer)
        // Concurrency: Set to number of partitions (one container per partition)
        // 각 컨테이너는 하나의 파티션을 담당하므로, 파티션 하나당 하나의 Consumer가 됨
        factory.setConcurrency(12); // order-events 토픽의 파티션 수: 12개
        
        // 가상 스레드 Executor 설정
        // SimpleAsyncTaskExecutor with virtual threads: 각 메시지 처리를 가상 스레드로 실행
        // 각 파티션의 메시지가 가상 스레드에서 처리됨
        SimpleAsyncTaskExecutor asyncTaskExecutor = new SimpleAsyncTaskExecutor("kafka-listener-");
        asyncTaskExecutor.setVirtualThreads(true); // 가상 스레드 활성화
        asyncTaskExecutor.setConcurrencyLimit(SimpleAsyncTaskExecutor.UNBOUNDED_CONCURRENCY); // 무제한 동시성
        factory.getContainerProperties().setListenerTaskExecutor(asyncTaskExecutor);
        
        // 수동 커밋 모드: @Transactional과 함께 사용 시 트랜잭션 커밋 시 자동으로 offset 커밋
        // BATCH 모드: 트랜잭션이 성공적으로 커밋되면 배치의 모든 offset이 자동으로 커밋됨
        factory.getContainerProperties().setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.BATCH);
        
        return factory;
    }
}
