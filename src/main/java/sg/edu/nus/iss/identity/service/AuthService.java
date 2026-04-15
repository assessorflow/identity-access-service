package sg.edu.nus.iss.identity.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.edu.nus.iss.identity.dto.request.LoginRequest;
import sg.edu.nus.iss.identity.dto.request.RefreshTokenRequest;
import sg.edu.nus.iss.identity.dto.request.RegisterRequest;
import sg.edu.nus.iss.identity.dto.response.AuthResponse;
import sg.edu.nus.iss.identity.dto.response.AuthUserResponse;
import sg.edu.nus.iss.identity.dto.response.RefreshResponse;
import sg.edu.nus.iss.identity.entity.Roles;
import sg.edu.nus.iss.identity.entity.Session;
import sg.edu.nus.iss.identity.entity.User;
import sg.edu.nus.iss.identity.exception.ServiceException;
import sg.edu.nus.iss.identity.repository.SessionRepository;
import sg.edu.nus.iss.identity.repository.UserRepository;
import sg.edu.nus.iss.identity.security.UserContextCache;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserContextCache userContextCache;
    private final MeterRegistry meterRegistry;

    /**
     * Public registration — only allows "assessor" role.
     * Admin accounts must be created via registerAdmin() which requires ADMIN auth.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (!Roles.ASSESSOR.equals(request.getRole())) {
            throw ServiceException.badRequest("Public registration only allows 'assessor' role");
        }
        return createUser(request);
    }

    /**
     * Admin-only registration — allows any valid role (assessor, admin).
     * Called from the protected endpoint.
     */
    @Transactional
    public AuthResponse registerAdmin(RegisterRequest request) {
        if (!Roles.ALL.contains(request.getRole())) {
            throw ServiceException.badRequest("Invalid role. Must be one of: " + Roles.ALL);
        }
        return createUser(request);
    }

    private AuthResponse createUser(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw ServiceException.conflict("Email already registered");
        }

        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .role(request.getRole())
                .build();
        user = userRepository.save(user);

        meterRegistry.counter("auth.register.success", "role", request.getRole()).increment();
        log.info("User registered: userId={}, role={}", user.getId(), user.getRole());
        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> {
                    meterRegistry.counter("auth.login.failure", "reason", "not_found").increment();
                    return ServiceException.unauthorized("Invalid email or password");
                });

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            meterRegistry.counter("auth.login.failure", "reason", "bad_password").increment();
            throw ServiceException.unauthorized("Invalid email or password");
        }
        if (!user.getIsActive()) {
            meterRegistry.counter("auth.login.failure", "reason", "deactivated").increment();
            throw ServiceException.forbidden("Account is deactivated");
        }

        meterRegistry.counter("auth.login.success").increment();
        log.info("User logged in: userId={}", user.getId());
        return buildAuthResponse(user);
    }

    @Transactional
    public RefreshResponse refreshToken(RefreshTokenRequest request) {
        Session session = sessionRepository.findByRefreshToken(request.getRefreshToken())
                .orElseThrow(() -> ServiceException.unauthorized("Invalid refresh token"));

        if (session.getExpiresAt().isBefore(Instant.now())) {
            sessionRepository.delete(session);
            throw ServiceException.unauthorized("Refresh token expired");
        }

        User user = session.getUser();
        if (!user.getIsActive()) {
            throw ServiceException.forbidden("Account is deactivated");
        }

        meterRegistry.counter("auth.token.refresh.success").increment();
        String accessToken = jwtService.generateAccessToken(user);
        return RefreshResponse.builder()
                .accessToken(accessToken)
                .expiresIn(jwtService.getAccessTokenExpirationMs() / 1000)
                .build();
    }

    @Transactional
    public void logout(UUID userId) {
        userContextCache.evictUserContext(userId);
        sessionRepository.deleteAllByUserId(userId);
        log.info("User logged out: userId={}", userId);
    }

    private AuthResponse buildAuthResponse(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        Session session = Session.builder()
                .user(user)
                .refreshToken(refreshToken)
                .expiresAt(Instant.now().plusMillis(jwtService.getRefreshTokenExpirationMs()))
                .build();
        sessionRepository.save(session);

        userContextCache.cacheUserContext(user);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(jwtService.getAccessTokenExpirationMs() / 1000)
                .user(toAuthUserResponse(user))
                .build();
    }

    private AuthUserResponse toAuthUserResponse(User user) {
        return AuthUserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole())
                .build();
    }
}
