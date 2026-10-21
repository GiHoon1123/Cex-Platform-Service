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
    
    // 직접 생성하는 팩터리에서도 Spring 설정의 소비자 시작 여부를 적용한다.
    @Value("${spring.kafka.listener.auto-startup:true}")
    private boolean listenerAutoStartup;

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
     * 금융 결과를 컨테이너 하나가 순서대로 소비한다. 가상 스레드는 수신 루프의
     * 실행 스레드이며 이벤트별 금융 작업을 따로 병렬 실행하는 설정이 아니다.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = 
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setAutoStartup(listenerAutoStartup);
        // 금융 이벤트 처리 실패를 기본 재시도 횟수 후 버리지 않는다. 실패한
        // 수신 기록과 금융 변경은 롤백하고 소비를 중단한다. 원인을 해결한 뒤
        // 재시작하면 미확정 오프셋부터 재수신하며 영속 수신 키로 중복을 막는다.
        factory.setCommonErrorHandler(new org.springframework.kafka.listener.CommonContainerStoppingErrorHandler());
        
        // 초기 토픽 한 파티션·단일 금융 수신 루프를 명시한다.
        factory.setConcurrency(1);

        SimpleAsyncTaskExecutor asyncTaskExecutor = new SimpleAsyncTaskExecutor("kafka-listener-");
        asyncTaskExecutor.setVirtualThreads(true); // 가상 스레드 활성화
        factory.getContainerProperties().setListenerTaskExecutor(asyncTaskExecutor);
        
        // 디비 커밋과 브로커 오프셋은 별개다. 정상 배치 뒤 오프셋을 확정하고,
        // 그 사이 종료로 재수신한 이벤트는 영속 수신 키로 중복 반영을 막는다.
        factory.getContainerProperties().setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.BATCH);
        
        return factory;
    }
}
