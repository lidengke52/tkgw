package com.dk.ipproxy.dynamic.gateway.config;

import com.dk.ipproxy.dynamic.gateway.constants.Protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * 动态白名单配置
 */
public class WhiteListConfig {
    public String ip;
    public int uid;
    public Map<Integer, PortDynamicProxyInfo> portDynamicProxyInfoMap = new HashMap<>();

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public int getUid() {
        return uid;
    }

    public void setUid(int uid) {
        this.uid = uid;
    }

    public Map<Integer, PortDynamicProxyInfo> getPortDynamicProxyInfoMap() {
        return portDynamicProxyInfoMap;
    }

    public void setPortDynamicProxyInfoMap(Map<Integer, PortDynamicProxyInfo> portDynamicProxyInfoMap) {
        this.portDynamicProxyInfoMap = portDynamicProxyInfoMap;
    }

    @Override
    public String toString() {
        return "WhiteListConfig{" +
                "ip='" + ip + '\'' +
                ", uid=" + uid +
                ", portDynamicProxyInfoMap=" + portDynamicProxyInfoMap +
                '}';
    }

    public static class PortDynamicProxyInfo {
        public String area = "";
        public String region = "";
        public String city = "";
        public String protocol = Protocol.HTTP;
        public int minutes;

        public String getArea() {
            return area;
        }

        public void setArea(String area) {
            this.area = area;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }

        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        public int getMinutes() {
            return minutes;
        }

        public void setMinutes(int minutes) {
            this.minutes = minutes;
        }

        @Override
        public String toString() {
            return "PortDynamicProxyInfo{" +
                    "area='" + area + '\'' +
                    ", region='" + region + '\'' +
                    ", city='" + city + '\'' +
                    ", protocol='" + protocol + '\'' +
                    ", minutes=" + minutes +
                    '}';
        }
    }
}
