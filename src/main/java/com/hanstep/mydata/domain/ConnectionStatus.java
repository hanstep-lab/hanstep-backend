package com.hanstep.mydata.domain;

/** 기관 연동 상태(data_connections.status). 동의 상태와 별개. */
public enum ConnectionStatus {
    PENDING, CONNECTED, EXPIRED, REVOKED, ERROR
}
