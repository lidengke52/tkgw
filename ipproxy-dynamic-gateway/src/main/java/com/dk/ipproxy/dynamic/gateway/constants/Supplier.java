package com.dk.ipproxy.dynamic.gateway.constants;

import java.util.List;

/**
 * 供应商
 */
public class Supplier {
    public static class MergePool {
        public static final int INFATICA = 16;
        public static final int NETNUT = 17;

        public static final List<Integer> ALL = List.of(INFATICA, NETNUT);
    }
}
