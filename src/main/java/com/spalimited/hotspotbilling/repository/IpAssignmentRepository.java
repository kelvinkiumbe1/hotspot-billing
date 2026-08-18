package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.IpAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IpAssignmentRepository extends JpaRepository<IpAssignment, Long> {

    List<IpAssignment> findBySubnetIdOrderByAddressAsc(Long subnetId);

    Optional<IpAssignment> findBySubnetIdAndAddress(Long subnetId, String address);

    List<IpAssignment> findBySubscriberId(Long subscriberId);

    long countBySubnetId(Long subnetId);

    void deleteBySubnetId(Long subnetId);
}
