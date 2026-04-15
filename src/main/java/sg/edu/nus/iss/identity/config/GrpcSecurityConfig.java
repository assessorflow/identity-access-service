package sg.edu.nus.iss.identity.config;

import net.devh.boot.grpc.server.security.authentication.GrpcAuthenticationReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Disables Spring Security integration for the gRPC server.
 * gRPC endpoints are internal (service-to-service within the K8s cluster).
 * Security relies on K8s network policies for pod-to-pod isolation — not Kong (Kong only proxies external HTTP).
 * If mTLS is needed later (e.g., Istio), replace this with a certificate-based GrpcAuthenticationReader.
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
