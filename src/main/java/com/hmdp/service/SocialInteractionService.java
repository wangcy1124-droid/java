package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.BlogLike;
import com.hmdp.mapper.SocialMapper;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import static com.hmdp.utils.RedisConstants.*;

@Service
public class SocialInteractionService {
    private final RedissonClient redisson;
    private final SocialTransactionService transactions;
    private final SocialProjectionService projection;
    private final SocialMapper mapper;
    public SocialInteractionService(RedissonClient redisson, SocialTransactionService transactions,
                                    SocialProjectionService projection, SocialMapper mapper) {
        this.redisson = redisson; this.transactions = transactions; this.projection = projection; this.mapper = mapper;
    }

    public Result like(Long blog, Boolean desired) {
        Long user = UserHolder.getUser().getId();
        if (blog == null || blog <= 0) return Result.fail("博客不存在");
        return locked(true, blog, () -> {
            BlogLike change = transactions.setLike(blog, user, desired);
            if (change == null) return Result.fail("博客不存在");
            projection.change(true, blog, user, change.getLikedAt() != null, change.getLikedAt());
            return Result.ok();
        });
    }

    public Result follow(Long target, Boolean active) {
        Long user = UserHolder.getUser().getId();
        if (target == null || target <= 0 || active == null) return Result.fail("关注参数不合法");
        if (user.equals(target)) return Result.fail("不能关注自己");
        if (active && mapper.userExists(target) == 0) return Result.fail("用户不存在");
        return locked(false, user, () -> {
            transactions.setFollow(user, target, active);
            projection.change(false, user, target, active, null);
            return Result.ok();
        });
    }

    public void ensure(boolean likes, Long id) {
        if (projection.ready(likes,id)) return;
        locked(likes,id, () -> {
            if (!projection.ready(likes,id)) projection.rebuild(likes,id);
            return null;
        });
    }

    private <T> T locked(boolean likes, Long id, Supplier<T> action) {
        RLock lock = redisson.getLock(SOCIAL_LOCK_KEY + (likes ? "blog:" : "follow:") + id);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(2, TimeUnit.SECONDS);
            if (!acquired) throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "操作频繁，请稍后重试");
            // 独立事务代理返回后再同步 Redis；锁覆盖提交与缓存写入，避免乱序覆盖。
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("社交操作被中断", e);
        } finally { if (acquired && lock.isHeldByCurrentThread()) lock.unlock(); }
    }
}
