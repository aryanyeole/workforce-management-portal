package com.aryanyeole.wmp.common.repository;

import com.aryanyeole.wmp.common.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
