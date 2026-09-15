package com.hmdp.entity;

import lombok.Data;

@Data
public class OrderStockRelease {
    private Long orderId;
    private Long userId;
    private Long voucherId;
    private String reservationId;
    private Boolean completed;
}
