package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.StaffUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StaffUserRepository extends JpaRepository<StaffUser, Long> {

    Optional<StaffUser> findByUsername(String username);

    Optional<StaffUser> findByUsernameAndActiveTrue(String username);

    List<StaffUser> findAllByOrderByCreatedAtAsc();

    long countByRoleAndActiveTrue(StaffUser.Role role);
}
