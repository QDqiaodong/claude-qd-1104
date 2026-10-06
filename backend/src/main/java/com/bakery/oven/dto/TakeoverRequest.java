package com.bakery.oven.dto;

import java.util.List;

/**
 * 故障接管请求：一批「工单 → 替代烤箱」迁移项。
 * 整批原子生效：任一项校验失败，全部不落库。
 */
public class TakeoverRequest {

    public List<Item> items;

    public static class Item {
        /** 待迁移的工单 id */
        public Long orderId;
        /** 替代烤箱 id */
        public Long targetOvenId;
    }
}
