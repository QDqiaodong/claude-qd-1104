package com.bakery.oven.repository;

import com.bakery.oven.entity.Oven;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OvenRepository extends JpaRepository<Oven, Long> {

    Optional<Oven> findByCode(String code);

    List<Oven> findByStatus(String status);

    /** 悲观写锁（SELECT ... FOR UPDATE）：故障接管时锁定源烤箱与替代烤箱，并发批次串行化 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Oven o where o.id = :id")
    Optional<Oven> findByIdForUpdate(@Param("id") Long id);
}
