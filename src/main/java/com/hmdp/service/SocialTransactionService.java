package com.hmdp.service;

import com.hmdp.entity.BlogLike;
import com.hmdp.mapper.SocialMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SocialTransactionService {
    private final SocialMapper mapper;
    public SocialTransactionService(SocialMapper mapper) { this.mapper = mapper; }

    /** null desired 保留课程 toggle 语义；显式 true/false 支持请求重试幂等。 */
    @Transactional(rollbackFor = Exception.class)
    public BlogLike setLike(Long blog, Long user, Boolean desired) {
        if (mapper.lockBlog(blog) == null) return null;
        BlogLike old = mapper.like(blog, user);
        boolean active = desired == null ? old == null : desired;
        long time = old == null ? mapper.nowMillis() : old.getLikedAt();
        if (active && old == null) {
            if (mapper.addLike(blog, user, time) != 1 || mapper.incrementLike(blog) != 1)
                throw new IllegalStateException("点赞关系和计数更新失败 blogId=" + blog);
        } else if (!active && old != null) {
            if (mapper.removeLike(blog, user) != 1 || mapper.decrementLike(blog) != 1)
                throw new IllegalStateException("取消点赞关系和计数更新失败 blogId=" + blog);
        }
        BlogLike result = new BlogLike(); result.setBlogId(blog); result.setUserId(user);
        result.setLikedAt(active ? time : null);
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public void setFollow(Long user, Long target, boolean active) {
        if (mapper.lockUser(user) == null) throw new IllegalArgumentException("当前用户不存在");
        if (active) mapper.addFollow(user, target);
        else mapper.removeFollow(user, target);
    }
}
