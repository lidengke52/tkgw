package com.dk.ipproxy.dynamic.gateway.config;

import java.util.List;

public class SupplierConfig {
    private int supplierId;
    private String authUser;
    private String authPass;
    private int sessionMinKeepTime;
    private int sessionMaxKeepTime;
    private List<String> availableGateway;
    /** 国家地区大小写 **/
    private int areaCaseType;
    /** Any一次一换格式 **/
    private String tmpSessionAuthFormat;
    /** Any粘性session格式 **/
    private String stickSessionAuthFormat;
    /** 指定国家一次一换格式 **/
    private String countryTmpSessionAuthFormat;
    /** 指定国家粘性session格式 **/
    private String countryStickSessionAuthFormat;
    /** 指定省份一次一换格式 **/
    private String stateTmpSessionAuthFormat;
    /** 指定省份粘性session格式 **/
    private String stateStickSessionAuthFormat;
    /** 指定城市一次一换格式 **/
    private String cityTmpSessionAuthFormat;
    /** 指定城市粘性session格式 **/
    private String cityStickSessionAuthFormat;

    public int getAreaCaseType() {
        return areaCaseType;
    }

    public void setAreaCaseType(int areaCaseType) {
        this.areaCaseType = areaCaseType;
    }

    public List<String> getAvailableGateway() {
        return availableGateway;
    }

    public void setAvailableGateway(List<String> availableGateway) {
        this.availableGateway = availableGateway;
    }

    public int getSupplierId() {
        return supplierId;
    }

    public void setSupplierId(int supplierId) {
        this.supplierId = supplierId;
    }

    public String getAuthUser() {
        return authUser;
    }

    public void setAuthUser(String authUser) {
        this.authUser = authUser;
    }

    public String getAuthPass() {
        return authPass;
    }

    public void setAuthPass(String authPass) {
        this.authPass = authPass;
    }

    public int getSessionMinKeepTime() {
        return sessionMinKeepTime;
    }

    public void setSessionMinKeepTime(int sessionMinKeepTime) {
        this.sessionMinKeepTime = sessionMinKeepTime;
    }

    public int getSessionMaxKeepTime() {
        return sessionMaxKeepTime;
    }

    public void setSessionMaxKeepTime(int sessionMaxKeepTime) {
        this.sessionMaxKeepTime = sessionMaxKeepTime;
    }

    public String getTmpSessionAuthFormat() {
        return tmpSessionAuthFormat;
    }

    public void setTmpSessionAuthFormat(String tmpSessionAuthFormat) {
        this.tmpSessionAuthFormat = tmpSessionAuthFormat;
    }

    public String getStickSessionAuthFormat() {
        return stickSessionAuthFormat;
    }

    public void setStickSessionAuthFormat(String stickSessionAuthFormat) {
        this.stickSessionAuthFormat = stickSessionAuthFormat;
    }

    public String getCountryTmpSessionAuthFormat() {
        return countryTmpSessionAuthFormat;
    }

    public void setCountryTmpSessionAuthFormat(String countryTmpSessionAuthFormat) {
        this.countryTmpSessionAuthFormat = countryTmpSessionAuthFormat;
    }

    public String getCountryStickSessionAuthFormat() {
        return countryStickSessionAuthFormat;
    }

    public void setCountryStickSessionAuthFormat(String countryStickSessionAuthFormat) {
        this.countryStickSessionAuthFormat = countryStickSessionAuthFormat;
    }

    public String getStateTmpSessionAuthFormat() {
        return stateTmpSessionAuthFormat;
    }

    public void setStateTmpSessionAuthFormat(String stateTmpSessionAuthFormat) {
        this.stateTmpSessionAuthFormat = stateTmpSessionAuthFormat;
    }

    public String getStateStickSessionAuthFormat() {
        return stateStickSessionAuthFormat;
    }

    public void setStateStickSessionAuthFormat(String stateStickSessionAuthFormat) {
        this.stateStickSessionAuthFormat = stateStickSessionAuthFormat;
    }

    public String getCityTmpSessionAuthFormat() {
        return cityTmpSessionAuthFormat;
    }

    public void setCityTmpSessionAuthFormat(String cityTmpSessionAuthFormat) {
        this.cityTmpSessionAuthFormat = cityTmpSessionAuthFormat;
    }

    public String getCityStickSessionAuthFormat() {
        return cityStickSessionAuthFormat;
    }

    public void setCityStickSessionAuthFormat(String cityStickSessionAuthFormat) {
        this.cityStickSessionAuthFormat = cityStickSessionAuthFormat;
    }

    @Override
    public String toString() {
        return "SupplierConfig{" +
                "supplierId=" + supplierId +
                ", authUser='" + authUser + '\'' +
                ", authPass='" + authPass + '\'' +
                ", sessionMinKeepTime=" + sessionMinKeepTime +
                ", sessionMaxKeepTime=" + sessionMaxKeepTime +
                ", availableGateway=" + availableGateway +
                ", tmpSessionAuthFormat='" + tmpSessionAuthFormat + '\'' +
                ", stickSessionAuthFormat='" + stickSessionAuthFormat + '\'' +
                ", countryTmpAuthFormat='" + countryTmpSessionAuthFormat + '\'' +
                ", countrySessionAuthFormat='" + countryStickSessionAuthFormat + '\'' +
                ", stateTmpAuthFormat='" + stateTmpSessionAuthFormat + '\'' +
                ", stateSessionAuthFormat='" + stateStickSessionAuthFormat + '\'' +
                ", cityTmpAuthFormat='" + cityTmpSessionAuthFormat + '\'' +
                ", citySessionAuthFormat='" + cityStickSessionAuthFormat + '\'' +
                '}';
    }
}
