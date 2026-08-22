package com.aryanyeole.wmp.onboarding.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryanyeole.wmp.common.domain.Department;
import com.aryanyeole.wmp.common.repository.DepartmentRepository;
import com.aryanyeole.wmp.onboarding.domain.Employee;
import com.aryanyeole.wmp.onboarding.domain.EmploymentStatus;
import com.aryanyeole.wmp.support.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

/**
 * Phase 1 smoke test: migrations apply against a real Postgres container and
 * a round trip through the repository layer works, including the
 * department FK and the created_at/updated_at audit fields.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EmployeeRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Test
    void savesAndReloadsAnEmployeeWithItsDepartment() {
        Department engineering = new Department();
        engineering.setName("Engineering");
        departmentRepository.save(engineering);

        Employee employee = new Employee();
        employee.setDepartment(engineering);
        employee.setFirstName("Ada");
        employee.setLastName("Lovelace");
        employee.setEmail("ada.lovelace@example.com");
        employee.setHireDate(LocalDate.of(2024, 1, 15));
        employeeRepository.save(employee);

        Optional<Employee> reloaded = employeeRepository.findById(employee.getId());

        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getEmail()).isEqualTo("ada.lovelace@example.com");
        assertThat(reloaded.get().getEmploymentStatus()).isEqualTo(EmploymentStatus.ACTIVE);
        assertThat(reloaded.get().getDepartment().getName()).isEqualTo("Engineering");
        assertThat(reloaded.get().getCreatedAt()).isNotNull();
        assertThat(reloaded.get().getUpdatedAt()).isNotNull();
    }
}
