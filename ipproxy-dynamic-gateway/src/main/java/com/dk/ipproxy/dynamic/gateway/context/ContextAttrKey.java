package com.dk.ipproxy.dynamic.gateway.context;

import io.netty.util.AttributeKey;

public class ContextAttrKey {
    public static final AttributeKey<ChannelContext> CHANNEL_FORWARD_CONTEXT = AttributeKey.valueOf("channelForwardContext");
}
