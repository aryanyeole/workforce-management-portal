package com.aryanyeole.wmp.payroll.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.aryanyeole.wmp.common.api.PageResponse;
import com.aryanyeole.wmp.payroll.service.PayrollService;

import jakarta.validation.Valid;

/**
 * No @PreAuthorize and no role checks here — route authorization is
 * PermissionRegistry + RouteAuthorizationFilter's job (ADR 0001).
 * PAYROLL_ADMIN is the only role the registry admits to any of these
 * routes, so PayrollService needs no VisibilityScope for runs/items — a
 * payroll run is org-wide, not employee-owned.
 */
@RestController
@RequestMapping("/api/v1/payroll/runs")
public class PayrollRunController {

    private final PayrollService payrollService;

    public PayrollRunController(PayrollService payrollService) {
        this.payrollService = payrollService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PayrollRunResponse create(@Valid @RequestBody CreatePayrollRunRequest request) {
        return payrollService.createRun(request);
    }

    @GetMapping
    public PageResponse<PayrollRunResponse> list(@RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return payrollService.listRuns(page, size);
    }

    @GetMapping("/{id}")
    public PayrollRunResponse get(@PathVariable Long id) {
        return payrollService.getRun(id);
    }

    @GetMapping("/{id}/items")
    public List<PayrollItemResponse> listItems(@PathVariable Long id) {
        return payrollService.listItems(id);
    }

    @PostMapping("/{id}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public PayrollItemResponse createItem(@PathVariable Long id, @Valid @RequestBody CreatePayrollItemRequest request) {
        return payrollService.createItem(id, request);
    }
}
