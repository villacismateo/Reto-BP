package ec.bp.reto.poc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TransferenciasPocApplication {
    public static void main(String[] args) {
        SpringApplication.run(TransferenciasPocApplication.class, args);
    }
}
