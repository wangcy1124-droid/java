package com.hmdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.OrderStockReleaseMapper;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.enums.OrderStatus.*;

@Service
public class OrderLifecycleService {
    @Resource private VoucherOrderMapper orders;
    @Resource private SeckillVoucherMapper vouchers;
    @Resource private OrderStockReleaseMapper releases;

    public Result query(Long id) {
        VoucherOrder order = owned(id);
        return order == null ? Result.fail("订单不存在或无权访问；异步订单请稍后查询") : Result.ok(order);
    }

    private VoucherOrder owned(Long id) {
        VoucherOrder order = orders.selectById(id);
        return order != null && order.getUserId().equals(UserHolder.getUser().getId()) ? order : null;
    }

    /** 演示接口：仅修改数据库状态，不发起真实扣款。 */
    @Transactional(rollbackFor = Exception.class)
    public Result simulatePay(Long id) {
        VoucherOrder order = owned(id);
        if (order == null) return Result.fail("订单不存在或无权访问");
        if (order.getStatus() == PAID.getCode()) return Result.ok(id);
        if (order.getStatus() == CLOSED.getCode()) return Result.fail("订单已关闭");
        if (order.getStatus() != PENDING.getCode()) return Result.fail("订单状态不支持支付");
        // 与关单竞争同一订单行；截止时间统一使用数据库时钟。
        int changed = orders.update(null, pendingVersion(order)
                .apply("expire_time > CURRENT_TIMESTAMP")
                .set("status", PAID.getCode()).setSql("pay_time=CURRENT_TIMESTAMP,version=version+1"));
        return changed == 1 ? Result.ok(id) : Result.fail("订单状态已变化或已超时，请刷新订单");
    }

    public List<VoucherOrder> expiredAfter(long after) {
        return orders.selectList(new QueryWrapper<VoucherOrder>().eq("status", PENDING.getCode())
                .gt("id", after).apply("expire_time <= CURRENT_TIMESTAMP")
                .orderByAsc("id").last("LIMIT 100"));
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean close(Long id) {
        VoucherOrder order = orders.selectById(id);
        if (order == null || order.getStatus() != PENDING.getCode()) return false;
        if (orders.update(null, pendingVersion(order).apply("expire_time <= CURRENT_TIMESTAMP")
                .set("status", CLOSED.getCode()).setSql("version=version+1")) != 1) return false;
        if (vouchers.update(null, new UpdateWrapper<SeckillVoucher>().eq("voucher_id", order.getVoucherId())
                .setSql("stock=stock+1")) != 1) throw new IllegalStateException("关单库存记录不存在 orderId=" + id);
        // 数据库事务先提交；Redis 失败时这条持久化记录仍可供任务重试。
        if (releases.enqueue(id) != 1) throw new IllegalStateException("关单释放任务未保存 orderId=" + id);
        return true;
    }

    private UpdateWrapper<VoucherOrder> pendingVersion(VoucherOrder order) {
        return new UpdateWrapper<VoucherOrder>().eq("id", order.getId()).eq("status", PENDING.getCode())
                .eq("version", order.getVersion());
    }
}
