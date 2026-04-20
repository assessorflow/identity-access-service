package sg.edu.nus.iss.identity.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import sg.edu.nus.iss.identity.config.JwtProperties;
import sg.edu.nus.iss.identity.dto.request.GoogleSsoRequest;
import sg.edu.nus.iss.identity.dto.request.LoginRequest;
import sg.edu.nus.iss.identity.dto.request.RefreshTokenRequest;
import sg.edu.nus.iss.identity.dto.request.RegisterRequest;
import sg.edu.nus.iss.identity.dto.response.AuthResponse;
import sg.edu.nus.iss.identity.dto.response.RefreshResponse;
import sg.edu.nus.iss.identity.entity.User;
import sg.edu.nus.iss.identity.exception.ServiceException;
import sg.edu.nus.iss.identity.service.AuthService;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String REFRESH_COOKIE_NAME = "af_refresh_token";
    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final JwtProperties jwtProperties;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletResponse response) {
        AuthResponse body = authService.register(request);
        setRefreshCookie(response, body.getRefreshToken());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/register/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AuthResponse> registerAdmin(
            @Valid @RequestBody RegisterRequest request,
            HttpServletResponse response) {
        AuthResponse body = authService.registerAdmin(request);
        setRefreshCookie(response, body.getRefreshToken());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {
        AuthResponse body = authService.login(request);
        setRefreshCookie(response, body.getRefreshToken());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/google")
    public ResponseEntity<AuthResponse> google(
            @Valid @RequestBody GoogleSsoRequest request,
            HttpServletResponse response) {
        AuthResponse body = authService.authenticateViaGoogle(request.getCredential());
        setRefreshCookie(response, body.getRefreshToken());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/refresh")
    public ResponseEntity<RefreshResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String cookieToken,
            @RequestBody(required = false) RefreshTokenRequest body) {
        String token = (cookieToken != null && !cookieToken.isBlank())
                ? cookieToken
                : (body != null ? body.getRefreshToken() : null);
        if (token == null || token.isBlank()) {
            throw ServiceException.unauthorized("Refresh token missing");
        }
        RefreshTokenRequest req = new RefreshTokenRequest();
        req.setRefreshToken(token);
        return ResponseEntity.ok(authService.refreshToken(req));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal User user,
            HttpServletResponse response) {
        authService.logout(user.getId());
        clearRefreshCookie(response);
        return ResponseEntity.noContent().build();
    }

    private void setRefreshCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, token)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(jwtProperties.getRefreshTokenExpirationMs() / 1000)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
