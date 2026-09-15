-- M2 -> M3，一次性迁移；先停止应用，再执行。保留已关闭订单历史。
ALTER TABLE tb_voucher_order
  ADD COLUMN expire_time DATETIME NULL COMMENT '待支付截止时间',
  ADD COLUMN version INT NOT NULL DEFAULT 0,
  ADD COLUMN active_user_id BIGINT UNSIGNED GENERATED ALWAYS AS
    (CASE WHEN status <> 4 THEN user_id ELSE NULL END) STORED,
  DROP INDEX uk_order_user_voucher,
  ADD UNIQUE KEY uk_order_active_user_voucher (active_user_id, voucher_id),
  ADD KEY idx_order_expire (status, expire_time),
  ADD KEY idx_order_scan (status, id);

UPDATE tb_voucher_order SET expire_time = DATE_ADD(create_time, INTERVAL 15 MINUTE)
WHERE status = 1 AND expire_time IS NULL;

-- 与关单、MySQL 库存释放同事务写入；Redis 成功后标记完成。
CREATE TABLE tb_order_stock_release (
  order_id BIGINT NOT NULL PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  voucher_id BIGINT UNSIGNED NOT NULL,
  reservation_id VARCHAR(32) NULL COMMENT 'NULL 表示 M0/M1 历史订单',
  completed TINYINT NOT NULL DEFAULT 0,
  retry_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_release_retry (completed, retry_time, order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
