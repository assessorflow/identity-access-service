package sg.edu.nus.iss.identity.config;

import net.devh.boot.grpc.server.security.authentication.GrpcAuthenticationReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Disables Spring Security integration for the gRPC server.
 * gRPC endpoints are internal (service-to-service within the cluster)
 * and do not need JWT/Spring Security — Kong handles external auth.
 */
@Configuration
public class GrpcSecurityConfig {

    @Bean
    public GrpcAuthenticationReader grpcAuthenticationReader() {
        // Anonymous access — no authentication on gRPC calls.
        // These are internal calls from other services, not from end users.
        return (call, headers) -> null;
    }
}
