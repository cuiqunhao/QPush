package com.argo.qpush.core.entity;

/**
 * 客户端状态
 * Created by yaming_deng on 14-8-6.
 */
public interface ClientStatus {

    int Offline = 0;

    int Online = 1;

    int Sleep = 2;

    int Lost = 3;
}
