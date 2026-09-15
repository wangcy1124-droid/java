package com.hmdp.enums;

public enum SeckillResult {
    SUCCESS(0, null),
    OUT_OF_STOCK(1, "库存不足"),
    DUPLICATE_ORDER(2, "禁止重复下单"),
    NOT_READY(3, "秒杀券不存在或尚未准备就绪"),
    NOT_STARTED(4, "秒杀尚未开始"),
    ENDED(5, "秒杀已结束");

    private final long code;
    private final String message;

    SeckillResult(long code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getMessage() { return message; }

    public static SeckillResult fromCode(Long code) {
        for (SeckillResult result : values()) {
            if (code != null && result.code == code) return result;
        }
        throw new IllegalStateException("未知秒杀脚本结果: " + code);
    }
}
