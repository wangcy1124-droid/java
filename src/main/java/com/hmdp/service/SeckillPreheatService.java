package com.hmdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.hmdp.utils.RedisConstants.*;

/** 单实例启动预热；不承担 Redis 数据丢失后的在线库存恢复。 */
@Service
@Slf4j
public class SeckillPreheatService implements ApplicationRunner {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();
    static {
        SCRIPT.setLocation(new ClassPathResource("seckill_preheat.lua"));
        SCRIPT.setResultType(Long.class);
    }
    @Resource private SeckillVoucherMapper seckillVoucherMapper;
    @Resource private VoucherOrderMapper voucherOrderMapper;
    @Resource private StringRedisTemplate redis;
    @Value("${hmdp.seckill.zone:Asia/Shanghai}") private String zone;

    @Override
    public void run(ApplicationArguments args) {
        long after = 0;
        while (true) {
            List<SeckillVoucher> batch = seckillVoucherMapper.selectList(new QueryWrapper<SeckillVoucher>()
                    .gt("voucher_id", after).orderByAsc("voucher_id").last("LIMIT 100"));
            if (batch.isEmpty()) return;
            for (SeckillVoucher voucher : batch) {
                initialize(voucher, false);
                after = voucher.getVoucherId();
            }
        }
    }

    public void initialize(SeckillVoucher voucher, boolean newlyCreated) {
        Long id = voucher.getVoucherId();
        if (Boolean.TRUE.equals(redis.hasKey(SECKILL_META_KEY + id))) return;
        if (voucher.getStock() == null || voucher.getStock() < 0 || voucher.getBeginTime() == null
                || voucher.getEndTime() == null || !voucher.getEndTime().isAfter(voucher.getBeginTime())) {
            log.warn("跳过不合法的秒杀券预热 voucherId={}", id);
            return;
        }
        ZoneId zoneId = ZoneId.of(zone);
        List<String> args = new ArrayList<>();
        args.add(voucher.getStock().toString());
        args.add(Long.toString(voucher.getBeginTime().atZone(zoneId).toInstant().toEpochMilli()));
        args.add(Long.toString(voucher.getEndTime().atZone(zoneId).toInstant().toEpochMilli()));
        args.add(newlyCreated ? "1" : "0");
        if (!newlyCreated) {
            for (VoucherOrder order : voucherOrderMapper.selectList(new QueryWrapper<VoucherOrder>()
                    .select("user_id").eq("voucher_id", id)
                    .ne("status", com.hmdp.enums.OrderStatus.CLOSED.getCode()))) {
                args.add(order.getUserId().toString());
            }
        }
        Long result = redis.execute(SCRIPT, Arrays.asList(SECKILL_STOCK_KEY + id,
                SECKILL_ORDER_KEY + id, SECKILL_META_KEY + id), args.toArray());
        if (result == null || result < 0) {
            log.warn("秒杀券未预热：库存状态不完整，需停流核对，voucherId={}", id);
        } else {
            log.info("秒杀券预热完成 voucherId={} initialized={}", id, result == 1);
        }
    }
}
