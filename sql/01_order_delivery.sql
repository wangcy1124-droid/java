-- M1 -> M2：停流并确认旧 Stream 已消费完毕后执行一次。
-- 如已有重复数据，唯一索引会失败；先人工核对，不自动删除订单。
ALTER TABLE tb_voucher_order ADD UNIQUE KEY uk_order_user_voucher (user_id, voucher_id);
CREATE TABLE tb_order_delivery (
  order_id BIGINT NOT NULL PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  voucher_id BIGINT UNSIGNED NOT NULL,
  reservation_id VARCHAR(32) NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CREATED/CANCELLED',
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
