package com.hmdp.task;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.OrderLifecycleService;
import com.hmdp.service.OrderStockReleaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.util.List;

@Component
@Slf4j
public class OrderCloseTask {
    @Resource private OrderLifecycleService lifecycle;
    @Resource private OrderStockReleaseService releases;
    @Value("${hmdp.order.close-enabled:true}") private boolean enabled;
    private long after;

    @Scheduled(fixedDelayString = "${hmdp.order.close-delay-ms:10000}")
    public void scan() {
        if (!enabled) return;
        try {
            List<VoucherOrder> batch = lifecycle.expiredAfter(after);
            if (batch.isEmpty()) after = 0;
            for (VoucherOrder order : batch) {
                try {
                    lifecycle.close(order.getId());
                } catch (RuntimeException e) {
                    log.error("超时关单失败 orderId={} userId={} voucherId={}", order.getId(),
                            order.getUserId(), order.getVoucherId(), e);
                } finally {
                    // 每轮最多 100 条，游标前进，异常订单不阻塞后续批次；扫描到底后重来。
                    after = order.getId();
                }
            }
        } catch (RuntimeException e) {
            log.error("超时订单扫描失败", e);
        }
        try {
            releases.retryPending();
        } catch (RuntimeException e) {
            log.error("关单库存释放任务扫描失败", e);
        }
    }
}
