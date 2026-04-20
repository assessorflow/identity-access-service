package sg.edu.nus.iss.identity.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import sg.edu.nus.iss.identity.config.GoogleAuthProperties;
import sg.edu.nus.iss.identity.exception.ServiceException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleTokenVerifierService {

    private final GoogleAuthProperties props;

    private GoogleIdTokenVerifier verifier;

    @PostConstruct
    public void init() {
        if (props.getClientId() == null || props.getClientId().isBlank()) {
            log.warn("app.google.client-id is not set — Google SSO endpoint will fail until configured");
            return;
        }
        this.verifier = new GoogleIdTokenVerifier.Builder(
                        new NetHttpTransport(),
                        GsonFactory.getDefaultInstance())
                .setAudience(List.of(props.getClientId()))
                .setIssuers(List.of(
                        "https://accounts.google.com",
                        "accounts.google.com"))
                .build();
        log.info("GoogleTokenVerifier initialized (clientId={}, hostedDomain={})",
                props.getClientId(), props.getHostedDomain());
    }

    public Payload verify(String credential) {
        if (verifier == null) {
            throw ServiceException.badRequest("Google SSO is not configured on this server");
        }
        if (credential == null || credential.isBlank()) {
            throw ServiceException.unauthorized("Missing Google credential");
        }
        GoogleIdToken idToken;
        try {
            idToken = verifier.verify(credential);
        } catch (GeneralSecurityException | IOException e) {
            log.error("Google ID token verification error: {}", e.getMessage());
            throw ServiceException.unauthorized("Invalid Google credential");
        }
        if (idToken == null) {
            throw ServiceException.unauthorized("Invalid Google credential");
        }
        Payload payload = idToken.getPayload();
        if (!Boolean.TRUE.equals(payload.getEmailVerified())) {
            throw ServiceException.unauthorized("Google email not verified");
        }
        if (props.getHostedDomain() != null && !props.getHostedDomain().isBlank()) {
            Object hd = payload.get("hd");
            if (hd == null || !props.getHostedDomain().equals(hd.toString())) {
                throw ServiceException.forbidden("Not a member of authorized domain");
            }
        }
        return payload;
    }
}
