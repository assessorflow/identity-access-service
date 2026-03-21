package sg.edu.nus.iss.identity.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import sg.edu.nus.iss.identity.service.JwtService;

import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Serves the JWKS (JSON Web Key Set) endpoint.
 * GCP API Gateway fetches this to verify JWT signatures.
 */
@RestController
@RequiredArgsConstructor
public class JwksController {

    private final JwtService jwtService;

    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<Map<String, Object>> jwks() {
        RSAPublicKey publicKey = jwtService.getPublicKey();

        Map<String, Object> jwk = Map.of(
                "kty", "RSA",
                "alg", "RS256",
                "use", "sig",
                "kid", "assessorflow-key-1",
                "n", base64UrlEncode(publicKey.getModulus().toByteArray()),
                "e", base64UrlEncode(publicKey.getPublicExponent().toByteArray())
        );

        Map<String, Object> jwks = Map.of("keys", List.of(jwk));
        return ResponseEntity.ok(jwks);
    }

    private String base64UrlEncode(byte[] bytes) {
        // Strip leading zero byte if present (BigInteger sign bit)
        if (bytes.length > 0 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
