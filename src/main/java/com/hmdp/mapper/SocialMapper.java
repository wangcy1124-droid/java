package com.hmdp.mapper;

import com.hmdp.entity.BlogLike;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface SocialMapper {
    @Select("SELECT liked FROM tb_blog WHERE id=#{id} FOR UPDATE")
    Integer lockBlog(Long id);
    @Select("SELECT id FROM tb_user WHERE id=#{id} FOR UPDATE")
    Long lockUser(Long id);
    @Select("SELECT COUNT(*) FROM tb_user WHERE id=#{id}")
    int userExists(Long id);
    @Select("SELECT * FROM tb_blog_like WHERE blog_id=#{blog} AND user_id=#{user}")
    BlogLike like(@Param("blog") Long blog, @Param("user") Long user);
    @Select("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)")
    long nowMillis();
    @Insert("INSERT INTO tb_blog_like(blog_id,user_id,liked_at) VALUES(#{blog},#{user},#{time})")
    int addLike(@Param("blog") Long blog, @Param("user") Long user, @Param("time") long time);
    @Delete("DELETE FROM tb_blog_like WHERE blog_id=#{blog} AND user_id=#{user}")
    int removeLike(@Param("blog") Long blog, @Param("user") Long user);
    @Update("UPDATE tb_blog SET liked=liked+1 WHERE id=#{id}")
    int incrementLike(Long id);
    @Update("UPDATE tb_blog SET liked=liked-1 WHERE id=#{id} AND liked>0")
    int decrementLike(Long id);
    @Select("SELECT * FROM tb_blog_like WHERE blog_id=#{id} ORDER BY liked_at,user_id")
    List<BlogLike> likes(Long id);
    @Insert("INSERT INTO tb_follow(user_id,follow_user_id) VALUES(#{user},#{target}) ON DUPLICATE KEY UPDATE id=id")
    void addFollow(@Param("user") Long user, @Param("target") Long target);
    @Delete("DELETE FROM tb_follow WHERE user_id=#{user} AND follow_user_id=#{target}")
    void removeFollow(@Param("user") Long user, @Param("target") Long target);
    @Select("SELECT follow_user_id FROM tb_follow WHERE user_id=#{id}")
    List<Long> follows(Long id);
}
