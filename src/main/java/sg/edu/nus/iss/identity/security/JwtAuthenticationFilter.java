package sg.edu.nus.iss.identity.security;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import sg.edu.nus.iss.identity.entity.User;
import sg.edu.nus.iss.identity.repository.UserRepository;
import sg.edu.nus.iss.identity.service.JwtService;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final UserContextCache userContextCache;
    private final MeterRegistry meterRegistry;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);
        if (!jwtService.isTokenValid(token)) {
            log.warn("Invalid/expired JWT from IP={}", request.getRemoteAddr());
            meterRegistry.counter("auth.jwt.invalid").increment();
            filterChain.doFilter(request, response);
            return;
        }

        UUID userId = jwtService.extractUserId(token);

        // Try Redis cache first, fall back to DB
        Map<String, Object> cached = userContextCache.getUserContext(userId);
        if (cached != null) {
            String role = (String) cached.get("role");
            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
            // Build a lightweight User principal from cache for @AuthenticationPrincipal
            User principal = User.builder()
                    .id(userId)
                    .role(role)
                    .isActive(true)
                    .build();
            var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
            return;
        }

        // Cache miss — load from DB
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || !user.getIsActive()) {
            log.warn("JWT valid but user not found or deactivated: userId={}", userId);
            meterRegistry.counter("auth.jwt.user_inactive").increment();
            filterChain.doFilter(request, response);
            return;
        }

        // Repopulate cache for next request
        userContextCache.cacheUserContext(user);

        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().toUpperCase()));
        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }
}
