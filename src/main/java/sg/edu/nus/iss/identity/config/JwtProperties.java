package sg.edu.nus.iss.identity.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    private String privateKeyContent;                      // PEM content from GCP Secret Manager
    private String publicKeyContent;                       // PEM content from GCP Secret Manager
    private String issuer = "assessorflow";
    private String audience = "assessorflow-api";
    private long accessTokenExpirationMs = 900_000;        // 15 minutes
    private long refreshTokenExpirationMs = 604_800_000;   // 7 days
}
