package sg.edu.nus.iss.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class IdentityAccessServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityAccessServiceApplication.class, args);
    }
}
