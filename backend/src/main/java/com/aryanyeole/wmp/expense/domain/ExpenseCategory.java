package com.aryanyeole.wmp.expense.domain;

import com.aryanyeole.wmp.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "expense_categories")
@Getter
@Setter
@NoArgsConstructor
public class ExpenseCategory extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String name;

    private String description;
}
