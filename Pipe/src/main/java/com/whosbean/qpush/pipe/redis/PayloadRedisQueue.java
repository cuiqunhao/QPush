package com.whosbean.qpush.pipe.redis;

import com.google.common.collect.Lists;
import com.whosbean.qpush.core.entity.Payload;
import com.whosbean.qpush.core.service.PayloadService;
import com.whosbean.qpush.pipe.PayloadCursor;
import com.whosbean.qpush.pipe.PayloadQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import redis.clients.jedis.BinaryShardedJedis;

import java.util.List;

/**
 * Created by yaming_deng on 14-8-11.
 */
public class PayloadRedisQueue implements PayloadQueue, InitializingBean {

    public static final String QPUSH_PENDING = "qpush:pending";
    protected Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private PayloadService payloadService;

    @Autowired
    private RedisBucket redisBucket;

    private List<Payload> emptyList = Lists.newArrayList();

    @Override
    public void init() {

    }

    @Override
    public List<Payload> getNormalItems(PayloadCursor cursor) {
        BinaryShardedJedis jedis =  redisBucket.getResource();
        try {
            String key = String.format("qpush:{%s:%s}.q", cursor.getProduct().getId(), 0);
            List<Payload> ids = Lists.newArrayList();
            for (int i = 0; i < cursor.getLimit(); i++) {
                byte[] t = jedis.lpop(key.getBytes());
                if (t==null || t.length == 0){
                    break;
                }
                ids.add(payloadService.get(Long.parseLong(new String(t))));
            }
            if (ids.size() > 0){
                jedis.decrBy(QPUSH_PENDING.getBytes(), ids.size());
            }
            redisBucket.returnResource(jedis);
            return ids;
        } catch (Exception e) {
            logger.error("添加消息进Redis错误", e);
            redisBucket.returnBrokenResource(jedis);
        }

        return emptyList;
    }

    @Override
    public List<Payload> getBroadcastItems(PayloadCursor cursor) {
        BinaryShardedJedis jedis =  redisBucket.getResource();
        try {
            String key = String.format("qpush:{%s:%s}.q", cursor.getProduct().getId(), 1);
            List<Payload> ids = Lists.newArrayList();
            for (int i = 0; i < cursor.getLimit(); i++) {
                byte[] t = jedis.lpop(key.getBytes());
                if (t==null || t.length == 0){
                    break;
                }
                ids.add(payloadService.get(Long.parseLong(new String(t))));
            }
            if (ids.size() > 0){
                jedis.decrBy(QPUSH_PENDING.getBytes(), ids.size());
            }
            redisBucket.returnResource(jedis);
            return ids;
        } catch (Exception e) {
            logger.error("添加消息进Redis错误", e);
            redisBucket.returnBrokenResource(jedis);
        }

        return emptyList;
    }

    @Override
    public void add(Payload payload) {
        PayloadService.instance.add(payload);

        BinaryShardedJedis jedis =  redisBucket.getResource();
        try {
            String key = String.format("qpush:{%s:%s}.q", payload.getProductId(), payload.getBroadcast());
            jedis.rpush(key.getBytes(), String.valueOf(payload.getId()).getBytes());
            long total = jedis.incr(QPUSH_PENDING.getBytes());
            redisBucket.returnResource(jedis);
            logger.info("qpush.pending total = " + total);
        } catch (Exception e) {
            logger.error("添加消息进Redis错误", e);
            redisBucket.returnBrokenResource(jedis);
        }
    }


    @Override
    public void afterPropertiesSet() throws Exception {
        init();
    }
}
