package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //Shop shop = cacheClient.queryWithPassThrough(
         //       CACHE_SHOP_TTL, TimeUnit.MINUTES,CACHE_SHOP_KEY, id, Shop.class, this::getById);

        //互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);

        //Shop shop = cacheClient.queryWithLogicalExpire(
        //               CACHE_SHOP_TTL, TimeUnit.MINUTES,CACHE_SHOP_KEY, id, Shop.class, this::getById);


        if (shop == null) {
            return Result.fail("店铺不存在111");
        }

        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /*private Shop queryWithLogicalExpire(Long id){
        //1.从redis查询商铺缓冲
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //3.存在 返
            return null;
        }
        //JSon反序列化
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data =(JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        //判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        //过期
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);

        if(isLock){
            //TODO 开启独立线程 缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() ->{
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }

        return shop;
    }*/

    private Shop queryWithMutex(Long id){
        //1.从redis查询商铺缓冲
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在 返
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的是否是空值
        if(shopJson != null){
            return null;
        }
        //实现缓存重建
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);

            if(!isLock){
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.不存在 根据ID查询数据库
            shop = getById(id);
            //5.不存在 查询错误
            if(shop == null){
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", 2L, TimeUnit.MINUTES);
                //写入错误信息
                return null;
            }
            //6.存在 写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), 30L, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally{
            unlock(lockKey);
        }

        //7.返回
        return shop;
    }

    /*private Shop queryWithPassThrough(Long id){
        //1.从redis查询商铺缓冲
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在 返
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的是否是空值
        if(shopJson != null){
            return null;
        }
        //4.不存在 根据ID查询数据库
        Shop shop = getById(id);
        //5.不存在 查询错误
        if(shop == null){
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", 2L, TimeUnit.MINUTES);
            //写入错误信息
            return null;
        }
        //6.存在 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), 30L, TimeUnit.MINUTES);
        //7.返回
        return shop;
    }*/

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    private void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);

        Thread.sleep(200);

        //2.封装逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        //3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));

    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("ID错误");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓冲
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //判断是否需要查询
        if(x == null || y == null){
            Page<Shop> page = query().eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));

            return Result.ok(page.getRecords());
        }
        //计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //查询redis
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(15)

                );
        //解析出Id
        if(results == null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size() <= from){
            return Result.ok();
        }
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result ->{
            //获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });
        //根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
