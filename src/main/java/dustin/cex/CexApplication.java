package dustin.cex;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@EnableKafka
@EnableScheduling
@EnableResilientMethods
public class CexApplication {

	public static void main(String[] args) {
		SpringApplication.run(CexApplication.class, args);
	}

}
