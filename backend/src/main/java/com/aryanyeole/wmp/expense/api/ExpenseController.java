package com.aryanyeole.wmp.expense.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.aryanyeole.wmp.common.api.CursorPageResponse;
import com.aryanyeole.wmp.common.api.PageResponse;
import com.aryanyeole.wmp.common.security.AuthPrincipal;
import com.aryanyeole.wmp.expense.service.ExpenseService;

import jakarta.validation.Valid;

/**
 * No @PreAuthorize and no role checks here — route authorization is
 * PermissionRegistry + RouteAuthorizationFilter's job (ADR 0001); row-level
 * ownership is enforced inside ExpenseService via VisibilityScope.
 */
@RestController
@RequestMapping("/api/v1/expenses")
public class ExpenseController {

    private final ExpenseService expenseService;

    public ExpenseController(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseResponse create(@AuthenticationPrincipal AuthPrincipal principal,
                                   @Valid @RequestBody CreateExpenseRequest request) {
        return expenseService.create(principal, request);
    }

    @GetMapping
    public PageResponse<ExpenseResponse> list(@AuthenticationPrincipal AuthPrincipal principal,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return expenseService.list(principal, page, size);
    }

    @GetMapping("/categories")
    public List<ExpenseCategoryResponse> categories() {
        return expenseService.listCategories();
    }

    @GetMapping("/approvals")
    public CursorPageResponse<ExpenseResponse> approvals(@AuthenticationPrincipal AuthPrincipal principal,
                                                          @RequestParam(required = false) String cursor,
                                                          @RequestParam(defaultValue = "20") int size) {
        return expenseService.pendingApprovals(principal, cursor, size);
    }

    @GetMapping("/{id}")
    public ExpenseResponse get(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable Long id) {
        return expenseService.get(principal, id);
    }

    @PatchMapping("/{id}")
    public ExpenseResponse update(@AuthenticationPrincipal AuthPrincipal principal,
                                   @PathVariable Long id,
                                   @Valid @RequestBody UpdateExpenseRequest request) {
        return expenseService.update(principal, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable Long id) {
        expenseService.delete(principal, id);
    }

    @PostMapping("/{id}/submit")
    public ExpenseResponse submit(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable Long id) {
        return expenseService.submit(principal, id);
    }

    @PostMapping("/{id}/approve")
    public ExpenseResponse approve(@AuthenticationPrincipal AuthPrincipal principal,
                                    @PathVariable Long id,
                                    @RequestBody(required = false) ApprovalDecisionRequest request) {
        return expenseService.approve(principal, id, request);
    }

    @PostMapping("/{id}/reject")
    public ExpenseResponse reject(@AuthenticationPrincipal AuthPrincipal principal,
                                   @PathVariable Long id,
                                   @RequestBody(required = false) ApprovalDecisionRequest request) {
        return expenseService.reject(principal, id, request);
    }
}
