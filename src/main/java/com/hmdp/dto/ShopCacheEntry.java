package com.hmdp.dto;

import com.hmdp.entity.Shop;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShopCacheEntry {
    private Shop data;
    /** 热点逻辑截止时间（epoch 毫秒）；普通商户为 Long.MAX_VALUE。 */
    private long expireTime;
}
