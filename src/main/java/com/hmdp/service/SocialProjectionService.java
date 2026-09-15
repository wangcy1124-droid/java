package com.hmdp.service;

import com.hmdp.entity.BlogLike;
import com.hmdp.mapper.SocialMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.hmdp.utils.RedisConstants.*;

@Service
@Slf4j
public class SocialProjectionService {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();
    static { SCRIPT.setLocation(new ClassPathResource("social_projection.lua")); SCRIPT.setResultType(Long.class); }
    private final StringRedisTemplate redis;
    private final SocialMapper mapper;
    private final long ttl;
    public SocialProjectionService(StringRedisTemplate redis, SocialMapper mapper,
                                   @Value("${hmdp.social.cache-seconds:300}") long ttl) {
        if (ttl <= 0) throw new IllegalArgumentException("社交缓存 TTL 必须大于 0");
        this.redis = redis; this.mapper = mapper; this.ttl = ttl;
    }

    public boolean ready(boolean likes, Long id) {
        String key = key(likes, id);
        return likes ? redis.opsForZSet().score(key, "0") != null
                : Boolean.TRUE.equals(redis.opsForSet().isMember(key, "0"));
    }

    /** 调用方持有对应 blog/user 的社交锁，避免旧快照覆盖新的增量。 */
    public void rebuild(boolean likes, Long id) {
        List<String> args = new ArrayList<>(Arrays.asList(likes ? "zset" : "set", "replace", Long.toString(ttl)));
        if (likes) {
            for (BlogLike like : mapper.likes(id)) {
                args.add(like.getUserId().toString()); args.add(like.getLikedAt().toString());
            }
        } else for (Long target : mapper.follows(id)) args.add(target.toString());
        if (!Long.valueOf(1).equals(redis.execute(SCRIPT, Collections.singletonList(key(likes,id)), args.toArray())))
            throw new IllegalStateException("社交缓存重建失败");
    }

    public void change(boolean likes, Long id, Long user, boolean active, Long time) {
        try {
            Long result = redis.execute(SCRIPT, Collections.singletonList(key(likes,id)), likes ? "zset" : "set",
                    "change", Long.toString(ttl), active ? "1" : "0", user.toString(), time == null ? "0" : time.toString());
            if (Long.valueOf(-1).equals(result)) rebuild(likes,id);
            else if (!Long.valueOf(1).equals(result)) throw new IllegalStateException("社交缓存更新没有结果");
        } catch (RuntimeException e) {
            // DB 已提交，不能谎称回滚。删除缓存使后续读取从关系表重建；删除也失败则 TTL 兜底。
            log.error("社交关系已提交，Redis 同步失败 kind={} id={} userId={}", likes ? "like" : "follow", id, user, e);
            try { redis.delete(key(likes,id)); }
            catch (RuntimeException cleanup) { log.error("社交缓存删除失败，等待 TTL 重建 key={}", key(likes,id), cleanup); }
        }
    }

    public String key(boolean likes, Long id) { return (likes ? BLOG_LIKED_KEY : FOLLOW_KEY) + id; }
}
