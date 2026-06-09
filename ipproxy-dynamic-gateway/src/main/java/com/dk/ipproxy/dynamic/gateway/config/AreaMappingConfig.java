package com.dk.ipproxy.dynamic.gateway.config;

import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Map;

public class AreaMappingConfig {
    public int supplierId;
    public Map<String, AreaInfo> mappingStore = new HashMap<>();

    public AreaMappingConfig(int supplierId) {
        this.supplierId = supplierId;
    }

    public void addMapping(String standardCountry, String standardState, String standardCity, String mappingCountry, String mappingState, String mappingCity) {
        // 拼接映射key
        String mappingKey = getMappingKey(standardCountry, standardState, standardCity);

        // 检查设置值
        AreaInfo mappingAreaInfo = new AreaInfo();
        mappingAreaInfo.country = mappingCountry;
        mappingAreaInfo.state = mappingState;
        mappingAreaInfo.city = mappingCity;
        mappingStore.put(mappingKey, mappingAreaInfo);
    }

    private String getMappingKey(String standardCountry, String standardState, String standardCity) {
        // 拼接映射key
        String mappingKey = "";
        if (!StringUtils.isEmpty(standardCountry)) {
            mappingKey += standardCountry;
            if (!StringUtils.isEmpty(standardState)) {
                mappingKey += ":" + standardState;
                if (!StringUtils.isEmpty(standardCity)) {
                    mappingKey += ":" + standardCity;
                }
            }
        }
        return mappingKey.toLowerCase();
    }

    public int getMappingStoreSize() {
        return mappingStore.size();
    }

    public AreaInfo getMapping(String country, String state, String city) {
        return mappingStore.get(getMappingKey(country, state, city));
    }

    public static class AreaInfo {
        public String country;
        public String state;
        public String city;

        @Override
        public String toString() {
            return "AreaInfo{" +
                    "country='" + country + '\'' +
                    ", state='" + state + '\'' +
                    ", city='" + city + '\'' +
                    '}';
        }
    }
}
