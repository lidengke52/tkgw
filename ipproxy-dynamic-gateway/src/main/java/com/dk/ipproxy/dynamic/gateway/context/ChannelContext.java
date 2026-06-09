package com.dk.ipproxy.dynamic.gateway.context;

public class ChannelContext {
    private String rawConnectUser;
    private int uid;
    private String authUser;
    private String authPass;
    /** 转发指定国家，小写 **/
    private String forwardCountry;
    /** 转发指定省份，大小写 **/
    private String forwardState;
    /** 转发指定城市，大小写 **/
    private String forwardCity;
    /** 链接串的会话id **/
    private String forwardSessionId;
    /** 链接串会话轮转周期 **/
    private int forwardSessionKeepTime;

    private String bindForwardUsername;
    private String bindForwardPass;
    private String bindForwardHostname;
    private int bindForwardPort;
    private int bindForwardKeepTime;
    private int bindForwardSupplierId;
    private int bindForwardTime;

    public int getUid() {
        return uid;
    }

    public void setUid(int uid) {
        this.uid = uid;
    }

    public int getBindForwardTime() {
        return bindForwardTime;
    }

    public void setBindForwardTime(int bindForwardTime) {
        this.bindForwardTime = bindForwardTime;
    }

    public String getBindForwardUsername() {
        return bindForwardUsername;
    }

    public void setBindForwardUsername(String bindForwardUsername) {
        this.bindForwardUsername = bindForwardUsername;
    }

    public String getBindForwardPass() {
        return bindForwardPass;
    }

    public void setBindForwardPass(String bindForwardPass) {
        this.bindForwardPass = bindForwardPass;
    }

    public String getBindForwardHostname() {
        return bindForwardHostname;
    }

    public void setBindForwardHostname(String bindForwardHostname) {
        this.bindForwardHostname = bindForwardHostname;
    }

    public int getBindForwardPort() {
        return bindForwardPort;
    }

    public void setBindForwardPort(int bindForwardPort) {
        this.bindForwardPort = bindForwardPort;
    }

    public int getBindForwardKeepTime() {
        return bindForwardKeepTime;
    }

    public void setBindForwardKeepTime(int bindForwardKeepTime) {
        this.bindForwardKeepTime = bindForwardKeepTime;
    }

    public int getBindForwardSupplierId() {
        return bindForwardSupplierId;
    }

    public void setBindForwardSupplierId(int bindForwardSupplierId) {
        this.bindForwardSupplierId = bindForwardSupplierId;
    }

    public String getAuthPass() {
        return authPass;
    }

    public void setAuthPass(String authPass) {
        this.authPass = authPass;
    }

    public String getForwardCountry() {
        return forwardCountry;
    }

    public void setForwardCountry(String forwardCountry) {
        this.forwardCountry = forwardCountry;
    }

    public String getForwardState() {
        return forwardState;
    }

    public void setForwardState(String forwardState) {
        this.forwardState = forwardState;
    }

    public String getForwardCity() {
        return forwardCity;
    }

    public void setForwardCity(String forwardCity) {
        this.forwardCity = forwardCity;
    }

    public String getForwardSessionId() {
        return forwardSessionId;
    }

    public void setForwardSessionId(String forwardSessionId) {
        this.forwardSessionId = forwardSessionId;
    }

    public int getForwardSessionKeepTime() {
        return forwardSessionKeepTime;
    }

    public void setForwardSessionKeepTime(int forwardSessionKeepTime) {
        this.forwardSessionKeepTime = forwardSessionKeepTime;
    }

    public String getRawConnectUser() {
        return rawConnectUser;
    }

    public void setRawConnectUser(String rawConnectUser) {
        this.rawConnectUser = rawConnectUser;
    }

    public String getAuthUser() {
        return authUser;
    }

    public void setAuthUser(String authUser) {
        this.authUser = authUser;
    }
}
