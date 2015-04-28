package com.argo.qpush.gateway.keeper;

import com.argo.qpush.core.entity.*;
import com.argo.qpush.gateway.SentProgress;
import com.argo.qpush.gateway.ServerConfig;
import com.google.common.collect.Maps;
import com.notnoop.apns.APNS;
import com.notnoop.apns.ApnsService;
import com.notnoop.apns.ApnsServiceBuilder;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Created by yaming_deng on 14-8-13.
 */
public class APNSKeeper {

    protected static Logger logger = LoggerFactory.getLogger(APNSKeeper.class);

    private static Map<Integer, ApnsService> mapping = Maps.newConcurrentMap();

    private static ApnsDelegateFailedAdapter delegateAdapter = new ApnsDelegateFailedAdapter();

    public static ApnsService get(Product product){

        if (product.getClientTypeid().intValue() != ClientType.iOS){
            return null;
        }

        if (StringUtils.isBlank(product.getDevCertPath())
                || StringUtils.isBlank(product.getDevCertPass())
                || StringUtils.isBlank(product.getCertPath())
                || StringUtils.isBlank(product.getCertPass())){
            logger.error("Product iOS Push Service Miss Cert Path and Password. {}", product);
            return null;
        }

        ApnsService service = mapping.get(product.getId());
        if (service == null){
            boolean sandbox = (Boolean) ServerConfig.getConf().get("apns.sandbox");
             ApnsServiceBuilder builder =  APNS.newService();
            if (sandbox){
                builder.withCert(product.getDevCertPath(), product.getDevCertPass());
                builder.withSandboxDestination();
            }else{
                builder.withCert(product.getCertPath(), product.getCertPass());
            }
            service = builder.asPool(10).withDelegate(delegateAdapter).build();
            mapping.put(product.getId(), service);
        }
        return service;
    }

    /**
     *
     * 使用APNS服务推送到苹果
     *
     * @param progress
     * @param product
     * @param cc
     * @param message
     *
     */
    public static void push(SentProgress progress, Product product, Client cc, Payload message){
        String json = message.asJson();
        ApnsService service = get(product);
        if (service != null){
            try{
                if (StringUtils.isBlank(cc.getDeviceToken())){
                    progress.incrFailed();
                    message.addFailedClient(cc.getUserId(), new PushError(PushError.NoDevivceToken));
                }else {
                    service.push(cc.getDeviceToken(), json);
                    progress.incrSuccess();
                }
            }catch(Exception e){
                logger.error("Push Failed", e);
                progress.incrFailed();
                message.addFailedClient(cc.getUserId(), new PushError(PushError.iOSPushError, e.getMessage()));
            }
        }else{
            logger.error("iOS Push Service Not Found.");
            progress.incrFailed();
            message.addFailedClient(cc.getUserId(), new PushError(PushError.iOSPushConfigError));
        }
    }
}
