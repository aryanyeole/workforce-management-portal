package com.aryanyeole.wmp.onboarding.repository;

import com.aryanyeole.wmp.onboarding.domain.OnboardingDocument;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnboardingDocumentRepository extends JpaRepository<OnboardingDocument, Long> {

    List<OnboardingDocument> findByEmployeeId(Long employeeId);
}
