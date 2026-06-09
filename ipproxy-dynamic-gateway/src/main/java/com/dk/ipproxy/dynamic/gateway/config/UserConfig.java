package com.dk.ipproxy.dynamic.gateway.config;

import java.util.List;

public class UserConfig {
    private int userId;
    private String authUser;
    private String authPass;
    private boolean trafficEnable;
    private String trafficLimit;
    private List<Integer> availableSupplier;

    public List<Integer> getAvailableSupplier() {
        return availableSupplier;
    }

    public void setAvailableSupplier(List<Integer> availableSupplier) {
        this.availableSupplier = availableSupplier;
    }

    public int getUserId() {
        return userId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
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

    public boolean isTrafficEnable() {
        return trafficEnable;
    }

    public void setTrafficEnable(boolean trafficEnable) {
        this.trafficEnable = trafficEnable;
    }

    public String getTrafficLimit() {
        return trafficLimit;
    }

    public void setTrafficLimit(String trafficLimit) {
        this.trafficLimit = trafficLimit;
    }
}
