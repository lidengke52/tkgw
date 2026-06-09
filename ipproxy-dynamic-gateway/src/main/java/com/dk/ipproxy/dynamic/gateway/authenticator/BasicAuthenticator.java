package com.dk.ipproxy.dynamic.gateway.authenticator;

import com.dk.ipproxy.dynamic.gateway.config.GlobalConfigStore;
import com.dk.ipproxy.dynamic.gateway.config.UserConfig;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 账密验证以及解析
 */
public class BasicAuthenticator {
    private static final Logger logger = LoggerFactory.getLogger(BasicAuthenticator.class);

    public boolean identify(AuthInfo authInfo) {
        if (authInfo == null) {
            return false;
        }

        String authUser = authInfo.authUser;
        String authPass = authInfo.authPass;
        String country = authInfo.country;
        String sessionId = authInfo.sessionId;
        int keepTime = authInfo.keepTime;
        if (logger.isDebugEnabled()) {
            logger.debug("auth with username: {}, password: {}, country: {}, sessionId: {}, keepTime: {}", authUser, authPass, country, sessionId, keepTime);
        }
        if (StringUtils.isEmpty(authUser)) {
            return false;
        }
        if (StringUtils.isEmpty(authPass)) {
            return false;
        }

        // 验证账密
        Map<String, UserConfig> userConfigMap = GlobalConfigStore.value.getUserConfigMap();
        UserConfig userConfig = userConfigMap.get(authUser);
        if (userConfig == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("auth error, user not exists, user: {}", authUser);
            }
            return false;
        }
        if (!authPass.equals(userConfig.getAuthPass())) {
            if (logger.isDebugEnabled()) {
                logger.debug("auth error, password error, user: {}", authUser);
            }
            return false;
        }
        // 将用户ID设置进去
        authInfo.uid = userConfig.getUserId();
        return true;
    }

    /**
     * 解析连接串
     * {user}-res-{country}-{state}-{city}-sid-{randomID}-keeptime-{minutes}:{password}
     * 例如：HSKFG-res-US-sid-34957934-keeptime-5
     */
    public AuthInfo parseRawConnectUser(String username, String password) {
        try {
            int resIdx = username.indexOf("-res-");
            int sidIdx = username.indexOf("-sid-");
            int keepTimeIdx = username.indexOf("-keeptime-");

            // 解析
            String user;
            String country;
            String state;
            String city;
            String sessionId = "";
            int keepTime = 0;
            if (sidIdx * keepTimeIdx == 1) {
                if (resIdx == -1) {
                    return null;
                }
                // 一次一换
                user = username.substring(0, resIdx);
                String area = username.substring(resIdx + 5);
                String[] areaParts = area.split("-");
                if (areaParts.length == 1) {
                    country = areaParts[0];
                    state = "";
                    city = "";
                } else if (areaParts.length == 2) {
                    country = areaParts[0];
                    state = areaParts[1];
                    city = "";
                } else if (areaParts.length == 3) {
                    country = areaParts[0];
                    state = areaParts[1];
                    city = areaParts[2];
                } else {
                    return null;
                }
            } else if (sidIdx * keepTimeIdx < 0) {
                // 错误格式
                return null;
            } else {
                // 粘性会话
                if (!(keepTimeIdx > sidIdx && sidIdx > resIdx)) {
                    return null;
                }
                user = username.substring(0, resIdx);
                String area = username.substring(resIdx + 5, sidIdx);
                String[] areaParts = area.split("-");
                if (areaParts.length == 1) {
                    country = areaParts[0];
                    state = "";
                    city = "";
                } else if (areaParts.length == 2) {
                    country = areaParts[0];
                    state = areaParts[1];
                    city = "";
                } else if (areaParts.length == 3) {
                    country = areaParts[0];
                    state = areaParts[1];
                    city = areaParts[2];
                } else {
                    return null;
                }
                sessionId = username.substring(sidIdx + 5, keepTimeIdx);
                keepTime = Integer.parseInt(username.substring(keepTimeIdx + 10));
            }

            AuthInfo authInfo = new AuthInfo();
            authInfo.authUser = user;
            authInfo.authPass = password;
            authInfo.country = country;
            authInfo.state = state;
            authInfo.city = city;
            authInfo.sessionId = sessionId;
            authInfo.keepTime = keepTime;
            return authInfo;
        } catch (Exception e) {
            return null;
        }
    }


    public static class AuthInfo {
        private int uid;
        private String authUser;
        private String authPass;
        private String country;
        private String state;
        private String city;
        private String sessionId;
        private int keepTime;

        public String getState() {
            return state;
        }

        public void setState(String state) {
            this.state = state;
        }

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }

        public int getUid() {
            return uid;
        }

        public void setUid(int uid) {
            this.uid = uid;
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

        public String getCountry() {
            return country;
        }

        public void setCountry(String country) {
            this.country = country;
        }

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public int getKeepTime() {
            return keepTime;
        }

        public void setKeepTime(int keepTime) {
            this.keepTime = keepTime;
        }

        @Override
        public String toString() {
            return "AuthInfo{" +
                    "uid=" + uid +
                    ", authUser='" + authUser + '\'' +
                    ", authPass='" + authPass + '\'' +
                    ", country='" + country + '\'' +
                    ", state='" + state + '\'' +
                    ", city='" + city + '\'' +
                    ", sessionId='" + sessionId + '\'' +
                    ", keepTime=" + keepTime +
                    '}';
        }
    }
}
