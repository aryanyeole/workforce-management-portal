package com.aryanyeole.wmp.common.repository;

import com.aryanyeole.wmp.common.domain.ApprovalEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalEventRepository extends JpaRepository<ApprovalEvent, Long> {
}
