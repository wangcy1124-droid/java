package com.hmdp.enums;

/** 保留课程的状态码；3/5/6 不在本阶段开放流转。 */
public enum OrderStatus {
    PENDING(1), PAID(2), CLOSED(4);
    private final int code;
    OrderStatus(int code) { this.code = code; }
    public int getCode() { return code; }
}
