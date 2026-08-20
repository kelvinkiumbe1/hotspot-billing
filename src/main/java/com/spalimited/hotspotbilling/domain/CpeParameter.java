package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** The last value a CPE reported for one parameter. */
@Entity
@Table(name = "cpe_parameters",
        uniqueConstraints = @UniqueConstraint(columnNames = {"cpe_device_id", "name"}),
        indexes = @Index(name = "cpe_parameters_device_idx", columnList = "cpe_device_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CpeParameter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cpe_device_id", nullable = false)
    private Long cpeDeviceId;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(length = 1000)
    private String value;

    private Instant updatedAt;
}
