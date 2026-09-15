package com.hmdp.dto;

import lombok.Data;

@Data
public class OrderMessage {
    private Long orderId;
    private Long userId;
    private Long voucherId;
    private String reservationId;

    public void validate() {
        if (orderId == null || orderId <= 0 || userId == null || userId <= 0
                || voucherId == null || voucherId <= 0 || reservationId == null
                || !reservationId.matches("[a-f0-9]{32}")) {
            throw new IllegalArgumentException("无效订单消息");
        }
    }
}
