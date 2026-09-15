package com.hmdp.mapper;

import com.hmdp.dto.OrderMessage;
import com.hmdp.entity.OrderDelivery;
import org.apache.ibatis.annotations.*;

public interface OrderDeliveryMapper {
    @Insert("INSERT INTO tb_order_delivery(order_id,user_id,voucher_id,reservation_id) " +
            "VALUES(#{orderId},#{userId},#{voucherId},#{reservationId}) ON DUPLICATE KEY UPDATE order_id=order_id")
    void ensure(OrderMessage message);

    @Select("SELECT * FROM tb_order_delivery WHERE order_id=#{id} FOR UPDATE")
    OrderDelivery lock(Long id);

    @Update("UPDATE tb_order_delivery SET state=#{state} WHERE order_id=#{id}")
    void state(@Param("id") Long id, @Param("state") String state);
}
