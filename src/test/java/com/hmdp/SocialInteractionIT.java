package com.hmdp;

import com.hmdp.dto.*;
import com.hmdp.entity.*;
import com.hmdp.mapper.*;
import com.hmdp.service.*;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.redisson.api.RedissonClient;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static com.hmdp.utils.RedisConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"hmdp.order.close-enabled=false", "hmdp.order.recovery-delay-ms=3600000"})
@org.springframework.test.annotation.DirtiesContext
class SocialInteractionIT {
    @Autowired SocialInteractionService social;
    @Autowired IBlogService blogs;
    @Autowired IFollowService follows;
    @Autowired IUserService users;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate db;
    @Autowired RedissonClient redisson;
    @SpyBean SocialMapper mapper;
    @SpyBean SocialProjectionService projection;
    @SpyBean UserMapper userMapper;
    @SpyBean BlogMapper blogMapper;
    private static final AtomicLong PHONE = new AtomicLong(System.currentTimeMillis()%90000000);

    @AfterEach void cleanup() { UserHolder.removeUser(); reset(mapper, projection, userMapper, blogMapper); }

    @Test void concurrentDesiredLikeAndUnlikeAreIdempotent() throws Exception {
        long u=user(), b=blog(u);
        concurrent(30, i -> { as(u); assertTrue(social.like(b,true).getSuccess()); });
        assertLike(b,u,1, true);
        long time = mapper.like(b,u).getLikedAt();
        as(u); assertTrue(social.like(b,true).getSuccess());
        assertEquals(time, mapper.like(b,u).getLikedAt());
        concurrent(30, i -> { as(u); assertTrue(social.like(b,false).getSuccess()); });
        assertLike(b,u,0, false);
        assertFalse(redisson.getLock(SOCIAL_LOCK_KEY+"blog:"+b).isLocked());
    }

    @Test void concurrentDifferentUsersAndLegacyTogglePreserveCount() throws Exception {
        long author=user(), b=blog(author);
        List<Long> us=new ArrayList<>(); for(int i=0;i<12;i++)us.add(user());
        concurrent(12,i->{as(us.get(i)); social.like(b,true);});
        assertEquals(12, count(b)); assertEquals(12L, relations(b));
        long another=blog(author);
        concurrent(20,i->{as(author); blogs.likeBlog(another);});
        assertLike(another,author,0,false); // 兼容 toggle：偶数次真实切换回到未点赞
    }

    @Test void topFivePreservesRedisTimeOrderAndUsesOneUserBatch() {
        long author=user(), b=blog(author);
        List<Long> us=new ArrayList<>();for(int i=0;i<7;i++)us.add(user());
        Collections.reverse(us);
        for(int i=0;i<us.size();i++) {
            as(us.get(i));social.like(b,true);
            db.update("UPDATE tb_blog_like SET liked_at=? WHERE blog_id=? AND user_id=?",1000L+i,b,us.get(i));
        }
        redis.delete(BLOG_LIKED_KEY+b);
        clearInvocations(userMapper);
        assertEquals(us.subList(0,5), dtoIds(blogs.queryBlogLikesById(b)));
        verify(userMapper,times(1)).selectList(any());
        assertEquals(1000.0,redis.opsForZSet().score(BLOG_LIKED_KEY+b,us.get(0).toString()));
        assertTrue(redis.getExpire(BLOG_LIKED_KEY+b)>0);
    }

    @Test void duplicateFollowAndUnfollowAreIdempotentAndValidateTarget() throws Exception {
        long u=user(), target=user();
        concurrent(30,i->{as(u);assertTrue(follows.follow(target,true).getSuccess());});
        assertFollow(u,target,true);
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                ()->db.update("INSERT INTO tb_follow(user_id,follow_user_id) VALUES(?,?)",u,target));
        concurrent(30,i->{as(u);assertTrue(follows.follow(target,false).getSuccess());});
        assertFollow(u,target,false);
        as(u);
        assertFalse(follows.follow(u,true).getSuccess());
        assertFalse(follows.follow(899999999L,true).getSuccess());
    }

    @Test void concurrentFollowAndUnfollowFinishWithMatchingRedisAndDatabase() throws Exception {
        long u=user(), target=user();
        concurrent(24,i->{as(u);follows.follow(target,i%2==0);});
        long n=db.queryForObject("SELECT COUNT(*) FROM tb_follow WHERE user_id=? AND follow_user_id=?",Long.class,u,target);
        assertTrue(n==0 || n==1);
        assertFollow(u,target,n==1);
    }

    @Test void commonFollowRecoversMissingSetsAndBatchLoadsUsers() {
        long a=user(), b=user(), x=user(), y=user(), z=user();
        as(a); follows.follow(x,true); follows.follow(y,true); follows.follow(z,true);
        as(b); follows.follow(x,true); follows.follow(y,true);
        redis.delete(Arrays.asList(FOLLOW_KEY+a,FOLLOW_KEY+b));
        as(a); clearInvocations(userMapper);
        assertEquals(new HashSet<>(Arrays.asList(x,y)),new HashSet<>(dtoIds(follows.followCommons(b))));
        verify(userMapper,times(1)).selectBatchIds(anyCollection());
        assertTrue(redis.opsForSet().isMember(FOLLOW_KEY+a,"0"));
        as(b); follows.follow(x,false);
        as(a); assertEquals(Collections.singletonList(y),dtoIds(follows.followCommons(b)));
        assertEquals(Collections.emptyList(),dtoIds(follows.followCommons(user())));
    }

    @Test void migratedLikeIdentityAndTimestampSurviveProjectionRebuild() {
        long u=user(),b=blog(u),time=1700000000000L;
        // 模拟升级脚本导入后的状态：关系已持久化，Redis 仍是无哨兵的课程格式。
        db.update("INSERT INTO tb_blog_like(blog_id,user_id,liked_at) VALUES(?,?,?)",b,u,time);
        db.update("UPDATE tb_blog SET liked=1 WHERE id=?",b);
        redis.opsForZSet().add(BLOG_LIKED_KEY+b,Long.toString(u),time);
        social.ensure(true,b);
        assertEquals((double)time,redis.opsForZSet().score(BLOG_LIKED_KEY+b,Long.toString(u)));
        redis.delete(BLOG_LIKED_KEY+b); social.ensure(true,b);
        assertEquals((double)time,redis.opsForZSet().score(BLOG_LIKED_KEY+b,Long.toString(u)));
        assertEquals(1,count(b));
    }

    @Test void databaseFailureRollsBackRelationAndCountWithoutTouchingRedis() {
        long u=user(),b=blog(u); social.ensure(true,b);
        doThrow(new IllegalStateException("injected counter update failure")).when(mapper).incrementLike(b);
        as(u);assertThrows(IllegalStateException.class,()->social.like(b,true));
        assertLike(b,u,0,false);
        verify(projection,never()).change(anyBoolean(),anyLong(),anyLong(),anyBoolean(),any());
        assertFalse(redisson.getLock(SOCIAL_LOCK_KEY+"blog:"+b).isLocked());
    }

    @Test void redisProjectionFailureKeepsCommitAndRecoversOnRead() {
        long u=user(),b=blog(u);
        doThrow(new IllegalStateException("injected Redis projection write failure")).when(projection).rebuild(true,b);
        as(u); assertTrue(social.like(b,true).getSuccess());
        assertEquals(1,count(b)); assertEquals(1L,relations(b));
        assertFalse(redis.hasKey(BLOG_LIKED_KEY+b));
        reset(projection); social.ensure(true,b); assertLike(b,u,1,true);
        long target=user();
        doThrow(new IllegalStateException("injected follow projection failure")).when(projection).rebuild(false,u);
        assertTrue(follows.follow(target,true).getSuccess());
        assertFalse(redis.hasKey(FOLLOW_KEY+u));
        reset(projection); social.ensure(false,u); assertFollow(u,target,true);
    }

    @Test void incrementalWritesDoNotExtendSnapshotTtlForever() {
        long u=user(),b=blog(u); social.ensure(true,b);
        redis.expire(BLOG_LIKED_KEY+b,100,TimeUnit.SECONDS);
        long before=redis.getExpire(BLOG_LIKED_KEY+b,TimeUnit.MILLISECONDS);
        as(u);social.like(b,true);
        assertTrue(redis.getExpire(BLOG_LIKED_KEY+b,TimeUnit.MILLISECONDS)<=before);
        assertLike(b,u,1,true);
    }

    @Test void followFeedBatchesQueriesAndDoesNotRepeatEqualTimestampPages() {
        long reader=user(),author=user();as(reader);follows.follow(author,true);
        List<Long> expected=new ArrayList<>();for(int i=0;i<5;i++)expected.add(blog(author));
        long timestamp=System.currentTimeMillis();
        for(Long b:expected)redis.opsForZSet().add(FEED_KEY+reader,b.toString(),timestamp);
        as(reader);clearInvocations(blogMapper,userMapper);
        List<Long> received=new ArrayList<>();long max=timestamp;int offset=0;
        for(int i=0;i<3;i++) {
            ScrollResult page=(ScrollResult)blogs.queryBlogOfFollow(max,offset).getData();
            assertNotNull(page);
            for(Object value:page.getList())received.add(((Blog)value).getId());
            max=page.getMinTime();offset=page.getOffset();
        }
        assertEquals(5,received.size());assertEquals(new HashSet<>(expected),new HashSet<>(received));
        assertEquals(5,offset);
        verify(blogMapper,times(3)).selectBatchIds(anyCollection());
        verify(blogMapper,never()).selectById(any());
        verify(userMapper,times(3)).selectBatchIds(anyCollection());
        assertNull(blogs.queryBlogOfFollow(max,offset).getData());
    }

    @Test void missingBlogFailsAndNewBlogIgnoresClientCounters() {
        long u=user();as(u);
        assertFalse(social.like(899999999L,true).getSuccess());
        Blog b=new Blog().setShopId(1L).setTitle("M6 counters").setImages("/imgs/test.jpg")
                .setContent("M6").setLiked(999).setComments(999);
        assertTrue(blogs.saveBlog(b).getSuccess());
        assertEquals(0,count(b.getId()));
        assertEquals(0,db.queryForObject("SELECT comments FROM tb_blog WHERE id=?",Integer.class,b.getId()));
    }

    private int count(long b){return db.queryForObject("SELECT liked FROM tb_blog WHERE id=?",Integer.class,b);}
    private long relations(long b){return db.queryForObject("SELECT COUNT(*) FROM tb_blog_like WHERE blog_id=?",Long.class,b);}
    private void assertLike(long b,long u,int n,boolean active){
        assertEquals(n,count(b));assertEquals((long)n,relations(b));
        assertEquals(active,redis.opsForZSet().score(BLOG_LIKED_KEY+b,Long.toString(u))!=null);
    }
    private void assertFollow(long u,long t,boolean active){
        assertEquals(active?1L:0L,db.queryForObject("SELECT COUNT(*) FROM tb_follow WHERE user_id=? AND follow_user_id=?",Long.class,u,t));
        assertEquals(active,redis.opsForSet().isMember(FOLLOW_KEY+u,Long.toString(t)));
    }
    @SuppressWarnings("unchecked") private List<Long> dtoIds(Result r){
        assertTrue(r.getSuccess());List<Long> ids=new ArrayList<>();for(UserDTO u:(List<UserDTO>)r.getData())ids.add(u.getId());return ids;
    }
    private long user(){User u=new User().setPhone("133"+String.format("%08d",PHONE.incrementAndGet())).setNickName("M6 fixture");users.save(u);return u.getId();}
    private void as(long id){UserDTO u=new UserDTO();u.setId(id);UserHolder.saveUser(u);}
    private long blog(long author){as(author);Blog b=new Blog().setShopId(1L).setTitle("M6 fixture").setImages("/imgs/test.jpg").setContent("M6");assertTrue(blogs.saveBlog(b).getSuccess());return b.getId();}
    private interface Work{void run(int index);}
    private void concurrent(int n,Work work)throws Exception{
        ExecutorService pool=Executors.newFixedThreadPool(12);
        try{
            List<Callable<Void>> tasks=new ArrayList<>();
            for(int i=0;i<n;i++){final int index=i;tasks.add(()->{try{work.run(index);return null;}finally{UserHolder.removeUser();}});}
            for(Future<Void> f:pool.invokeAll(tasks))f.get();
        }finally{pool.shutdownNow();}
    }
}
