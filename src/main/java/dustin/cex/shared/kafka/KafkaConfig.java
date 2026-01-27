package dustin.cex.shared.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka 설정
 * Kafka Configuration
 * 
 * 역할:
 * - Kafka Producer 설정
 * - KafkaTemplate 빈 등록
 */
@Configuration
public class KafkaConfig {
    
    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;
    
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
}
