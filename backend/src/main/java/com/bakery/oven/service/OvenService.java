package com.bakery.oven.service;

import com.bakery.oven.common.BizException;
import com.bakery.oven.common.Dict;
import com.bakery.oven.dto.TakeoverRequest;
import com.bakery.oven.entity.BakeOrder;
import com.bakery.oven.entity.Oven;
import com.bakery.oven.repository.BakeOrderRepository;
import com.bakery.oven.repository.OvenRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class OvenService {

    private final OvenRepository ovenRepository;
    private final BakeOrderRepository orderRepository;

    public OvenService(OvenRepository ovenRepository, BakeOrderRepository orderRepository) {
        this.ovenRepository = ovenRepository;
        this.orderRepository = orderRepository;
    }

    public List<Oven> list(String status) {
        if (status != null && !status.isBlank()) {
            return ovenRepository.findByStatus(status);
        }
        return ovenRepository.findAll();
    }

    public Oven create(Oven input) {
        if (input.code == null || input.code.isBlank()) {
            throw new BizException("炉号不能为空");
        }
        if (ovenRepository.findByCode(input.code).isPresent()) {
            throw new BizException("炉号已存在：" + input.code);
        }
        if (input.capacity == null || input.capacity <= 0) {
            throw new BizException("烤箱容量必须大于 0");
        }
        Oven oven = new Oven();
        oven.code = input.code;
        oven.capacity = input.capacity;
        oven.status = (input.status != null && Dict.isValidOvenStatus(input.status)) ? input.status : "空闲";
        oven.createdAt = LocalDateTime.now();
        return ovenRepository.save(oven);
    }

    public Oven update(Long id, Oven input) {
        Oven oven = ovenRepository.findById(id)
                .orElseThrow(() -> new BizException("烤箱不存在：" + id));
        if (input.code != null) {
            if (input.code.isBlank()) {
                throw new BizException("炉号不能为空");
            }
            ovenRepository.findByCode(input.code).ifPresent(o -> {
                if (!o.id.equals(id)) {
                    throw new BizException("炉号已存在：" + input.code);
                }
            });
            oven.code = input.code;
        }
        if (input.capacity != null) {
            if (input.capacity <= 0) {
                throw new BizException("烤箱容量必须大于 0");
            }
            oven.capacity = input.capacity;
        }
        if (input.status != null && !input.status.isBlank()) {
            if (!Dict.isValidOvenStatus(input.status)) {
                throw new BizException("烤箱状态非法：" + input.status);
            }
            oven.status = input.status;
        }
        return ovenRepository.save(oven);
    }

    /** 置为故障 */
    public Oven breakOven(Long id) {
        Oven oven = ovenRepository.findById(id)
                .orElseThrow(() -> new BizException("烤箱不存在：" + id));
        oven.status = "故障";
        return ovenRepository.save(oven);
    }

    /** 修复为空闲 */
    public Oven repairOven(Long id) {
        Oven oven = ovenRepository.findById(id)
                .orElseThrow(() -> new BizException("烤箱不存在：" + id));
        oven.status = "空闲";
        return ovenRepository.save(oven);
    }

    /** 烤箱详情 · 故障接管：该炉当前可迁移的在炉工单（待烤 / 烘焙中） */
    public List<BakeOrder> takeoverOrders(Long ovenId) {
        ovenRepository.findById(ovenId)
                .orElseThrow(() -> new BizException("烤箱不存在：" + ovenId));
        return orderRepository.findByOvenIdAndStatusIn(ovenId, List.of("待烤", "烘焙中"));
    }

    /**
     * 故障接管：把源烤箱上的多张在炉工单整批迁移到各自选择的替代烤箱。
     *
     * 规则：
     *  - 替代烤箱必须空闲、容量不小于工单实际烤盘数，且不能同时接收第二张「烘焙中」工单；
     *  - 待烤迁移后仍为待烤；烘焙中迁移后保留烘焙中状态与原开始时间，替代烤箱转为占用；
     *  - 任一张不满足条件则整批不落库（事务回滚），提示定位到具体工单与原因；
     *  - 源烤箱 + 全部替代烤箱按 id 升序加悲观写锁：并发两批选中同一替代烤箱时，
     *    后一批阻塞到前一批提交后再校验，只会成功一批，且不会留下半迁移数据。
     */
    @Transactional
    public List<BakeOrder> takeover(Long sourceOvenId, List<TakeoverRequest.Item> items) {
        if (sourceOvenId == null) {
            throw new BizException("缺少源烤箱 id");
        }
        if (items == null || items.isEmpty()) {
            throw new BizException("请至少选择一张要迁移的工单");
        }

        // 1) 先加锁再读数：本事务首个数据库操作就是 FOR UPDATE 锁定，
        //    后续普通读的快照在拿到锁之后才建立，必然看到并发批次已提交的最新归属。
        List<Long> lockIds = new ArrayList<>();
        lockIds.add(sourceOvenId);
        for (TakeoverRequest.Item item : items) {
            if (item != null && item.targetOvenId != null && !lockIds.contains(item.targetOvenId)) {
                lockIds.add(item.targetOvenId);
            }
        }
        lockIds.sort(Long::compareTo);
        Map<Long, Oven> locked = new HashMap<>();
        for (Long ovenId : lockIds) {
            ovenRepository.findByIdForUpdate(ovenId).ifPresent(o -> locked.put(o.id, o));
        }
        Oven source = locked.get(sourceOvenId);
        if (source == null) {
            throw new BizException("烤箱不存在：" + sourceOvenId);
        }

        // 2) 逐张校验并收集全部错误（定位到工单与原因），任一失败整批不落库
        List<String> errors = new ArrayList<>();
        List<BakeOrder> migrated = new ArrayList<>();
        Map<Long, Oven> targetOf = new HashMap<>();
        Set<Long> seenOrderIds = new HashSet<>();
        Set<Long> bakingTargetIds = new HashSet<>();
        for (TakeoverRequest.Item item : items) {
            if (item == null || item.orderId == null) {
                errors.add("存在未指定工单的迁移项");
                continue;
            }
            Long orderId = item.orderId;
            if (!seenOrderIds.add(orderId)) {
                errors.add("工单 #" + orderId + " 在本批接管中被重复选择");
                continue;
            }
            BakeOrder order = orderRepository.findById(orderId).orElse(null);
            if (order == null) {
                errors.add("工单 #" + orderId + " 不存在");
                continue;
            }
            String label = "工单 #" + order.id + "（" + order.status + "·" + order.trays + "盘）";
            if (!sourceOvenId.equals(order.ovenId)) {
                errors.add(label + " 不在烤箱 " + source.code + " 上，可能已被其他接管批次迁走");
                continue;
            }
            if (!"待烤".equals(order.status) && !"烘焙中".equals(order.status)) {
                errors.add(label + " 只有「待烤 / 烘焙中」的工单才能迁移");
                continue;
            }
            Long targetOvenId = item.targetOvenId;
            if (targetOvenId == null) {
                errors.add(label + " 未选择替代烤箱");
                continue;
            }
            if (targetOvenId.equals(sourceOvenId)) {
                errors.add(label + " 替代烤箱不能是原烤箱 " + source.code);
                continue;
            }
            Oven target = locked.get(targetOvenId);
            if (target == null) {
                errors.add(label + " 替代烤箱不存在：" + targetOvenId);
                continue;
            }
            if (!"空闲".equals(target.status)) {
                errors.add(label + " 替代烤箱 " + target.code + " 当前为「" + target.status + "」，必须处于空闲");
                continue;
            }
            if (target.capacity < order.trays) {
                errors.add(label + " 替代烤箱 " + target.code + " 容量不足（需 " + order.trays
                        + " 盘，容量 " + target.capacity + "）");
                continue;
            }
            if ("烘焙中".equals(order.status)) {
                boolean busyInDb = !orderRepository.findByOvenIdAndStatus(target.id, "烘焙中").isEmpty();
                if (busyInDb || !bakingTargetIds.add(target.id)) {
                    errors.add(label + " 替代烤箱 " + target.code + " 已有一张烘焙中工单，不能再接收第二张");
                    continue;
                }
            }
            migrated.add(order);
            targetOf.put(order.id, target);
        }
        if (!errors.isEmpty()) {
            throw new BizException("故障接管失败，整批未迁移：" + String.join("；", errors));
        }

        // 3) 应用迁移：待烤仍为待烤；烘焙中保留状态与原开始时间，替代烤箱转为占用
        for (BakeOrder order : migrated) {
            Oven target = targetOf.get(order.id);
            order.ovenId = target.id;
            orderRepository.save(order);
            if ("烘焙中".equals(order.status)) {
                target.status = "占用";
                ovenRepository.save(target);
            }
        }
        // 源烤箱若因「占用」且烘焙中已迁走而空出，则回到空闲；故障状态保持原样
        if ("占用".equals(source.status)
                && orderRepository.findByOvenIdAndStatus(source.id, "烘焙中").isEmpty()) {
            source.status = "空闲";
            ovenRepository.save(source);
        }
        return migrated;
    }
}
