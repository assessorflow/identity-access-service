package sg.edu.nus.iss.identity.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import sg.edu.nus.iss.identity.config.GoogleAuthProperties;
import sg.edu.nus.iss.identity.exception.ServiceException;

import java.net.URL;
import java.text.ParseException;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleTokenVerifierService {

    /** Google's published JWKS — rotates periodically, cached with long-TTL + background refresh. */
    private static final String GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";

    private final GoogleAuthProperties props;

    private ConfigurableJWTProcessor<SecurityContext> jwtProcessor;

    @PostConstruct
    public void init() throws Exception {
        if (props.getClientId() == null || props.getClientId().isBlank()) {
            log.warn("app.google.client-id is not set — POST /api/v1/auth/google will refuse requests");
            return;
        }

        // JWKSourceBuilder refreshes the JWKS in the background and is tolerant
        // of transient network failures.
        JWKSource<SecurityContext> keySource = JWKSourceBuilder
                .create(new URL(GOOGLE_JWKS_URL), null)
                .build();
        JWSKeySelector<SecurityContext> keySelector =
                new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource);

        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(keySelector);
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                Set.of(props.getClientId()), // audience
                null,
                Set.of("iss", "aud", "exp", "iat", "sub"),
                Set.of()));
        this.jwtProcessor = processor;

        log.info("GoogleTokenVerifier initialized (clientId={}, hostedDomain={})",
                props.getClientId(), props.getHostedDomain());
    }

    public JWTClaimsSet verify(String credential) {
        if (jwtProcessor == null) {
            throw ServiceException.badRequest("Google SSO is not configured on this server");
        }
        if (credential == null || credential.isBlank()) {
            throw ServiceException.unauthorized("Missing Google credential");
        }
        JWTClaimsSet claims;
        try {
            claims = jwtProcessor.process(credential, null);
        } catch (ParseException e) {
            log.error("Malformed Google credential: {}", e.getMessage());
            throw ServiceException.unauthorized("Malformed Google credential");
        } catch (Exception e) {
            log.error("Google ID token verification failed: {}", e.getMessage());
            throw ServiceException.unauthorized("Invalid Google credential");
        }

        String issuer = claims.getIssuer();
        if (!"https://accounts.google.com".equals(issuer) && !"accounts.google.com".equals(issuer)) {
            throw ServiceException.unauthorized("Unexpected issuer: " + issuer);
        }

        Boolean emailVerified = claims.getBooleanClaim("email_verified");
        if (!Boolean.TRUE.equals(emailVerified)) {
            throw ServiceException.unauthorized("Google email not verified");
        }

        String expected = props.getHostedDomain();
        if (expected != null && !expected.isBlank()) {
            String hd = claims.getStringClaim("hd");
            if (hd == null || !expected.equals(hd)) {
                throw ServiceException.forbidden("Not a member of authorized domain");
            }
        }
        return claims;
    }
}
