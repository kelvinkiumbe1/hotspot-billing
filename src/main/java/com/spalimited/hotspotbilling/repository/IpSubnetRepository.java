package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.IpSubnet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IpSubnetRepository extends JpaRepository<IpSubnet, Long> {

    List<IpSubnet> findAllByOrderByNameAsc();

    Optional<IpSubnet> findByCidr(String cidr);

    List<IpSubnet> findByRouterId(Long routerId);
}
