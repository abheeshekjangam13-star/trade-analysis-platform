package com.tradeanalysis.strategy.notify;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface EmailNotificationRepository extends JpaRepository<EmailNotification, Long> {
    List<EmailNotification> findByStatusInAndAttemptsLessThanOrderByCreatedAtAsc(
            Collection<EmailNotification.Status> statuses, int attempts, Pageable page);
    List<EmailNotification> findAllByOrderByCreatedAtDesc(Pageable page);
    List<EmailNotification> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable page);
    boolean existsByUserIdAndKindAndCreatedAtAfter(Long userId, EmailNotification.Kind kind, java.time.Instant after);

    /** [userId, latest createdAt] for every user who was ever queued an email of this kind. */
    @org.springframework.data.jpa.repository.Query(
            "select e.userId, max(e.createdAt) from EmailNotification e where e.kind = :kind and e.userId is not null group by e.userId")
    List<Object[]> lastSentByKind(@org.springframework.data.repository.query.Param("kind") EmailNotification.Kind kind);
}
