package sg.edu.nus.iss.identity.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import sg.edu.nus.iss.identity.config.JwtProperties;
import sg.edu.nus.iss.identity.entity.User;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Caches user context in Redis for distributed access by other services.
 * Key format: user:{user_id} -> { user_id, workflow_id, role }
 * See redis_store.md Section 1.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserContextCache {

    private static final String KEY_PREFIX = "user:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final JwtProperties jwtProperties;

    public void cacheUserContext(User user) {
        String key = KEY_PREFIX + user.getId();
        Map<String, Object> context = new HashMap<>();
        context.put("user_id", user.getId().toString());
        context.put("workflow_id", "");
        context.put("role", user.getRole());

        redisTemplate.opsForHash().putAll(key, context);
        redisTemplate.expire(key, jwtProperties.getRefreshTokenExpirationMs(), TimeUnit.MILLISECONDS);
        log.debug("Cached user context for user_id={}", user.getId());
    }

    public void updateWorkflowId(UUID userId, String workflowId) {
        String key = KEY_PREFIX + userId;
        redisTemplate.opsForHash().put(key, "workflow_id", workflowId);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getUserContext(UUID userId) {
        String key = KEY_PREFIX + userId;
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        if (entries.isEmpty()) {
            return null;
        }
        Map<String, Object> context = new HashMap<>();
        entries.forEach((k, v) -> context.put(k.toString(), v));
        return context;
    }

    public void evictUserContext(UUID userId) {
        redisTemplate.delete(KEY_PREFIX + userId);
        log.debug("Evicted user context for user_id={}", userId);
    }
}
