package com.bakery.oven.repository;

import com.bakery.oven.entity.BakeOrder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BakeOrderRepository extends JpaRepository<BakeOrder, Long> {

    List<BakeOrder> findByStatus(String status);

    List<BakeOrder> findByOvenId(Long ovenId);

    List<BakeOrder> findByOvenIdAndStatus(Long ovenId, String status);

    List<BakeOrder> findByOvenIdAndStatusIn(Long ovenId, List<String> statuses);
}
