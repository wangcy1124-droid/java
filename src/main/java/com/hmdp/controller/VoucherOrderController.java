package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.OrderLifecycleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Resource private OrderLifecycleService lifecycle;

    @GetMapping("/{id}")
    public Result query(@PathVariable Long id) { return lifecycle.query(id); }

    @PostMapping("/{id}/simulate-pay")
    public Result simulatePay(@PathVariable Long id) { return lifecycle.simulatePay(id); }
    @Resource
    private IVoucherOrderService  voucherOrderService;
    @PostMapping("seckill/{id}")
    @com.hmdp.annotation.RateLimit(limit = 5, windowSeconds = 10, dimension = com.hmdp.enums.RateLimitDimension.USER)
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }
}
