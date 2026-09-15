package com.hmdp.mapper;

import com.hmdp.entity.OrderStockRelease;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface OrderStockReleaseMapper {
    @Insert("INSERT INTO tb_order_stock_release(order_id,user_id,voucher_id,reservation_id) " +
            "SELECT o.id,o.user_id,o.voucher_id,d.reservation_id FROM tb_voucher_order o " +
            "LEFT JOIN tb_order_delivery d ON d.order_id=o.id WHERE o.id=#{id}")
    int enqueue(Long id);

    @Select("SELECT * FROM tb_order_stock_release WHERE completed=0 AND retry_time<=CURRENT_TIMESTAMP " +
            "ORDER BY retry_time,order_id LIMIT 100")
    List<OrderStockRelease> pending();

    @Select("SELECT * FROM tb_order_stock_release WHERE order_id=#{id}")
    OrderStockRelease find(Long id);

    @Update("UPDATE tb_order_stock_release SET completed=1 WHERE order_id=#{id} AND completed=0")
    int complete(Long id);

    @Update("UPDATE tb_order_stock_release SET retry_time=DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 10 SECOND) " +
            "WHERE order_id=#{id} AND completed=0")
    void defer(Long id);
}
