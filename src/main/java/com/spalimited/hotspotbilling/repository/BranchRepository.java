package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.Branch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BranchRepository extends JpaRepository<Branch, Long> {

    Optional<Branch> findByName(String name);

    List<Branch> findAllByOrderByNameAsc();
}
