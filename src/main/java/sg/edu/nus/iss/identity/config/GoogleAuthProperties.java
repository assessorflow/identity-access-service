package sg.edu.nus.iss.identity.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.google")
public class GoogleAuthProperties {

    private String clientId;

    private String hostedDomain;
}
