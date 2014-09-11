package com.whosbean.qpush.gateway.dispatch;

import com.whosbean.qpush.core.MetricBuilder;
import com.whosbean.qpush.core.entity.ClientType;
import com.whosbean.qpush.core.entity.Payload;
import com.whosbean.qpush.core.entity.PayloadStatus;
import com.whosbean.qpush.core.entity.Product;
import com.whosbean.qpush.core.service.ClientService;
import com.whosbean.qpush.core.service.PayloadService;
import com.whosbean.qpush.gateway.SentProgress;
import com.whosbean.qpush.gateway.keeper.ClientKeeper;
import com.whosbean.qpush.pipe.PayloadCursor;
import com.whosbean.qpush.pipe.PayloadQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 *
 * 每个产品一个Dispatcher
 *
 * Created by yaming_deng on 14-8-8.
 */
public class Dispatcher extends Thread {

    public static final String DISPATCHER_INTERVAL = "dispatcher.interval";
    public static final String DISPATCHER_THREAD_MIN = "dispatcher.thread_min";
    public static final String DISPATCHER_THREAD_MAX = "dispatcher.thread_max";
    public static final String DISPATCHER_BROADCAST_LIMIT = "dispatcher.broadcast_limit";

    protected static Logger logger = LoggerFactory.getLogger(Dispatcher.class);

    private Properties conf;
    private PayloadQueue queue;
    private Product product;

    private PayloadCursor singleCursor;
    private PayloadCursor broadcastCursor;

    private ThreadPoolTaskExecutor singlePool;
    private ThreadPoolTaskExecutor broadcastPool;

    private volatile boolean stopping;

    public Dispatcher(Properties conf, Product product, PayloadQueue queue) {
        this.conf = conf;
        this.queue = queue;
        this.product = product;
        this.singleCursor = new PayloadCursor(product);
        this.broadcastCursor = new PayloadCursor(product);
        this.singlePool = this.createPool();
        this.broadcastPool = this.createPool();
        this.stopping = false;
    }

    protected ThreadPoolTaskExecutor createPool() {
        int min = Integer.parseInt(this.conf.getProperty(DISPATCHER_THREAD_MIN, "10"));
        int max = Integer.parseInt(this.conf.getProperty(DISPATCHER_THREAD_MAX, "100"));

        ThreadPoolTaskExecutor exe = new ThreadPoolTaskExecutor();
        exe.setCorePoolSize(min);
        exe.setMaxPoolSize(max);
        exe.setWaitForTasksToCompleteOnShutdown(true);
        exe.afterPropertiesSet();

        return exe;
    }

    @Override
    public void run(){

        logger.info("Dispatcher start to run. " + this.product);

        final int min = Integer.parseInt(this.conf.getProperty(DISPATCHER_INTERVAL, "1000"));

        // p2p 推送
        Thread thread1 = new Thread(new Runnable() {

            @Override
            public void run() {
                while (!stopping) {
                    int total = doSinglePush();
                    //如果有消息，则把等待时间缩短.
                    int ts = min;
                    if (total > 0){
                        ts = min / 2;
                    }
                    try {
                        Thread.sleep(ts);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        thread1.start();

        // 广播 推送
        Thread thread2 = new Thread(new Runnable() {

            @Override
            public void run() {
                while (!stopping) {
                    int total = doBroadcastPush();
                    //如果有消息，则把等待时间缩短.
                    int ts = min;
                    if (total > 0){
                        ts = min / 2;
                    }
                    try {
                        Thread.sleep(ts);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        thread2.start();

        //TODO: 离线

        logger.info("Dispatcher stop running. " + this.product);
    }

    public void stopDispatch(){
        this.stopping = true;
    }

    public void startDispatch(){
        this.stopping = false;
    }

    public void pushOfflinePayload(String userId){
        this.singlePool.submit(new OfflineSendThread(this.product, userId));
    }

    protected int doBroadcastPush() {
        List<Payload> items = queue.getBroadcastItems(this.broadcastCursor);
        if (items.size() == 0){
            return 0;
        }

        logger.info("Dispatcher Broadcast, " + product + ", total = " + items.size());

        final SentProgress overall = new SentProgress(items.size());

        for(final Payload item : items){
            //every item for one thread.
            this.broadcastPool.submit(new Runnable() {

                @Override
                public void run() {

                    int total0 = ClientKeeper.count(product.getAppKey());
                    int total1 = 0;
                    if (product.getClientTypeid().intValue() == ClientType.iOS) {
                        total1 = ClientService.instance.countOfflineByType(product.getId(), ClientType.iOS);
                    }
                    if (total0 + total1 == 0) {
                        saveBoradcastStatus(item, 0);
                    } else {
                        //sending progress
                        SentProgress progress1 = new SentProgress(total0 + total1);
                        //step1
                        if (total0 > 0) {
                            doBoradcastToClients(item, total0, progress1);
                        }
                        //step2
                        if (total1 >0) {
                            doBoradcastToIOSClients(item, total1, progress1);
                        }
                        //wait to be finished.
                        try {
                            progress1.getCountDownLatch().await();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        //step4
                        saveBoradcastStatus(item, progress1.getSuccess().get());
                        logger.info("Broadcast Summary. id=" + item.getId() + ", " + progress1);
                    }

                    overall.incrSuccess();
                }
            });

        }

        try {
            overall.getCountDownLatch().await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        int size = items.size();
        broadcastCursor.setStartId(items.get(size - 1).getId());
        broadcastCursor.setTs(new Date());

        return size;
    }

    protected int doSinglePush() {
        List<Payload> items = queue.getNormalItems(this.singleCursor);
        if (items.size() == 0){
            return 0;
        }

        logger.info("Dispatcher Single, " + product + ", total = " + items.size());

        SentProgress overall = new SentProgress(items.size());
        for(Payload item : items){
            // create a single thread for a single message.
            this.singlePool.submit(new OneSendThread(this.product, item, overall));
        }

        try {
            overall.getCountDownLatch().await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        int size = items.size();
        singleCursor.setStartId(items.get(size - 1).getId());
        singleCursor.setTs(new Date());

        return size;
    }

    protected void doBoradcastToClients(Payload message, int total, SentProgress progress){
        //每个线程发送100个客户端.
        int limit = Integer.parseInt(this.conf.getProperty(DISPATCHER_BROADCAST_LIMIT, "100"));
        int pages = total / limit;
        if(total % limit > 0){
            pages ++;
        }
        for(int i=0; i<pages; i++){
            this.broadcastPool.submit(new BroadcastThread(this.product, message, i, limit, progress));
        }
    }

    private void saveBoradcastStatus(Payload message, int total) {
        try {
            if (message.getStatusId().intValue() == PayloadStatus.Pending0){
                message.setTotalUsers(total);
                message.setSentDate(new Date().getTime()/1000);
                message.setStatusId(PayloadStatus.Sent);
                PayloadService.instance.saveWithId(message);
            }else {
                PayloadService.instance.updateSendStatus(message, total);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (total > 0) {
            MetricBuilder.pushMeter.mark(total);
            MetricBuilder.boradcastMeter.mark(total);
        }
    }

    protected void doBoradcastToIOSClients(Payload message, int total, SentProgress progress){
        //每个线程发送100个客户端.
        int limit = Integer.parseInt(this.conf.getProperty(DISPATCHER_BROADCAST_LIMIT, "100"));
        long pages = total / limit;
        if(total % limit > 0){
            pages ++;
        }
        for(int i=0; i<pages; i++){
            this.broadcastPool.submit(new BroadcastIOSThread(this.product, message, i, limit, progress));
        }
    }

    public Product getProduct() {
        return product;
    }
}
