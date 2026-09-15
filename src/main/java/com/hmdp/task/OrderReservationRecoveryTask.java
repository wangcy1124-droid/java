package com.hmdp.task;

import com.hmdp.service.OrderCompensationService;
import com.hmdp.service.OrderReservationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import javax.annotation.Resource;
import java.util.Set;

@Configuration
@EnableScheduling
@Slf4j
public class OrderReservationRecoveryTask {
    @Resource private OrderReservationService reservations;
    @Resource private OrderCompensationService compensation;
    @Value("${hmdp.order.recovery-age-ms:300000}") private long ageMs;

    @Scheduled(fixedDelayString = "${hmdp.order.recovery-delay-ms:10000}")
    public void recover() {
        try {
            Set<String> tokens = reservations.overdue(ageMs);
            if (tokens == null) return;
            for (String token : tokens) {
                try {
                    compensation.cancel(reservations.read(token), "STALE_RESERVATION");
                } catch (RuntimeException e) {
                    log.error("库存占用恢复失败，记录保留 reservationId={}", token, e);
                    // 将失败项移到后面，避免前 100 个异常记录饿死后续待补偿订单。
                    reservations.defer(token);
                }
            }
        } catch (RuntimeException e) {
            log.error("无法扫描未完成库存占用", e);
        }
    }
}
