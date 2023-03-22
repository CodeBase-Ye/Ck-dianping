package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements  ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_prefix = "lock:";
    private static final String ID_prefix = UUID.randomUUID()
            .toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static{
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取当前线程
        String threadId =ID_prefix +  Thread.currentThread().getId();
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_prefix + name, threadId , timeoutSec, TimeUnit.SECONDS);

        return Boolean.TRUE.equals(success);
    }
    @Override
    public void unlock() {
        //调用lua
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_prefix + name),
                ID_prefix + Thread.currentThread().getId());


    }

//    @Override
//    public void unlock() {
//        String threadId =ID_prefix +  Thread.currentThread().getId();
//        //获取锁中标识
//        String id = stringRedisTemplate.opsForValue().get(KEY_prefix + name);
//
//        if(threadId.equals(id)){
//            //释放锁
//            stringRedisTemplate.delete(KEY_prefix + name);
//        }
//
//    }
}
