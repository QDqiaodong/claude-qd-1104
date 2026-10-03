package com.bakery.oven.repository;

import com.bakery.oven.entity.Oven;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OvenRepository extends JpaRepository<Oven, Long> {

    Optional<Oven> findByCode(String code);

    List<Oven> findByStatus(String status);
}
