package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.Expense;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    List<Expense> findByIncurredOnBetweenOrderByIncurredOnDesc(LocalDate from, LocalDate to);

    List<Expense> findTop200ByOrderByIncurredOnDesc();
}
