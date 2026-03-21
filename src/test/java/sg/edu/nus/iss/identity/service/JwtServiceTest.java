package sg.edu.nus.iss.identity.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import sg.edu.nus.iss.identity.entity.User;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class JwtServiceTest {

    @Autowired
    private JwtService jwtService;

    private User buildTestUser() {
        return User.builder()
                .id(UUID.randomUUID())
                .email("test@example.com")
                .fullName("Test User")
                .role("assessor")
                .build();
    }

    @Test
    void generateAndValidateAccessToken() {
        User user = buildTestUser();
        String token = jwtService.generateAccessToken(user);

        assertThat(jwtService.isTokenValid(token)).isTrue();
        assertThat(jwtService.extractUserId(token)).isEqualTo(user.getId());
    }

    @Test
    void generateAndValidateRefreshToken() {
        User user = buildTestUser();
        String token = jwtService.generateRefreshToken(user);

        assertThat(jwtService.isTokenValid(token)).isTrue();
        assertThat(jwtService.extractUserId(token)).isEqualTo(user.getId());
    }

    @Test
    void invalidToken_returnsFalse() {
        assertThat(jwtService.isTokenValid("garbage.token.here")).isFalse();
    }

    @Test
    void parseToken_containsClaims() {
        User user = buildTestUser();
        String token = jwtService.generateAccessToken(user);
        var claims = jwtService.parseToken(token);

        assertThat(claims.get("email")).isEqualTo("test@example.com");
        assertThat(claims.get("role")).isEqualTo("assessor");
        assertThat(claims.get("name")).isEqualTo("Test User");
        assertThat(claims.getIssuer()).isEqualTo("assessorflow");
    }
}
