-- 停止旧应用后执行一次；已有重复关注时唯一约束会失败，不自动删除用户数据。
ALTER TABLE tb_follow
  ADD UNIQUE KEY uk_follow_user_target (user_id, follow_user_id),
  ADD KEY idx_follow_target (follow_user_id);

CREATE TABLE tb_blog_like (
  blog_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  liked_at BIGINT NOT NULL COMMENT '点赞时间 epoch 毫秒',
  PRIMARY KEY (blog_id, user_id),
  KEY idx_blog_like_time (blog_id, liked_at, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 旧环境启动 M6 前还需运行 scripts/migrate-social-likes.py 导入 Redis 中已知的点赞身份。
-- 保留 tb_blog.liked 原总数；课程数据只有总数而没有用户身份的部分不伪造关系。
