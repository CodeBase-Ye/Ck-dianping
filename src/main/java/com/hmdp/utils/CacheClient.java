package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){

        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(Long time, TimeUnit unit,
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback){
        //1.从redis查询商铺缓冲
        String json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        //2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //3.存在 返
            return JSONUtil.toBean(json, type);
        }
        //判断命中的是否是空值
        if(json != null){
            return null;
        }
        //4.不存在 根据ID查询数据库
        R r = dbFallback.apply(id);
        //5.不存在 查询错误
        if(r == null){
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", 2L, TimeUnit.MINUTES);
            //写入错误信息
            return null;
        }
        //6.存在 写入redis
        this.set(keyPrefix + id, r,time,unit);
        //7.返回
        return r;
    }

    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R,ID> R queryWithLogicalExpire(
            Long time, TimeUnit unit, String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback){
        //1.从redis查询商铺缓冲
        String json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        //2.判断是否存在
        if (StrUtil.isBlank(json)) {
            //3.存在 返
            return null;
        }
        //JSon反序列化
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data =(JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();

        //判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        //过期
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);

        if(isLock){
            //TODO 开启独立线程 缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() ->{
                try {
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(keyPrefix + id, r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }

        return r;
    }

    public boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    public void unlock(String key){
        stringRedisTemplate.delete(key);
    }


}
