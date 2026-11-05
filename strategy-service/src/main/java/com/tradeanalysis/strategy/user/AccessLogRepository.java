package com.tradeanalysis.strategy.user;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {
    List<AccessLog> findAllByOrderByCreatedAtDesc(Pageable page);
    List<AccessLog> findByUsernameIgnoreCaseOrderByCreatedAtDesc(String username, Pageable page);
}
