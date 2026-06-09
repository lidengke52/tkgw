package com.dk.ipproxy.dynamic.gateway.handler;

import com.dk.ipproxy.dynamic.gateway.cache.ChannelStateStore;
import com.dk.ipproxy.dynamic.gateway.filter.IPFilter;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

@ChannelHandler.Sharable
public class ConnectionManageHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ConnectionManageHandler.class);

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        // src IP 过滤
        InetSocketAddress inetSocketAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        String srcIP = inetSocketAddress.getAddress().getHostAddress();
        if (IPFilter.isBlocked(srcIP)) {
            logger.info("block connect from srcIP: {}", srcIP);
            ctx.close();
        } else {
            super.channelRegistered(ctx);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("channel activate, channelId: {}", ctx.channel().id());
        }
        ChannelStateStore.addChannel(ctx.channel());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("channel inactive, channelId: {}", ctx.channel().id());
        }
        ChannelStateStore.removeChannel(ctx.channel());
        super.channelInactive(ctx);
    }
}
