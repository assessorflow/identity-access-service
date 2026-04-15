package sg.edu.nus.iss.identity.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sg.edu.nus.iss.identity.repository.SessionRepository;

import java.time.Instant;

/**
 * Purges expired refresh token sessions from the database every hour.
 * Sessions have a 7-day TTL (refresh token expiration) — without cleanup,
 * the sessions table grows unbounded.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionCleanupScheduler {

    private final SessionRepository sessionRepository;

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void purgeExpiredSessions() {
        int deleted = sessionRepository.deleteExpiredSessions(Instant.now());
        if (deleted > 0) {
            log.info("Purged {} expired sessions", deleted);
        }
    }
}
