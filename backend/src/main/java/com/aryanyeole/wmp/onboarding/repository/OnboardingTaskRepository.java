package com.aryanyeole.wmp.onboarding.repository;

import com.aryanyeole.wmp.onboarding.domain.OnboardingTask;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnboardingTaskRepository extends JpaRepository<OnboardingTask, Long> {

    List<OnboardingTask> findByEmployeeId(Long employeeId);
}
