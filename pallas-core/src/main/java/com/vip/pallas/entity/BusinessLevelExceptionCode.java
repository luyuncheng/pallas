package com.vip.pallas.entity;

public enum BusinessLevelExceptionCode {
    SC_UNAUTHORIZED(401),HTTP_FORBIDDEN(403),HTTP_INTERNAL_SERVER_ERROR(500);

    BusinessLevelExceptionCode(int code) {
        this.code = code;
    }

    private int code;

    public int val(){return this.code;}
}
