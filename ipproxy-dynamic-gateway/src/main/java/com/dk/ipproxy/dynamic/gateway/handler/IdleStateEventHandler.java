package com.dk.ipproxy.dynamic.gateway.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;

/**
 * 连接空闲处理Handler
 * 断开连接
 */
public class IdleStateEventHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(IdleStateEventHandler.class);

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            handleIdleState(ctx, (IdleStateEvent) evt);
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    private void handleIdleState(ChannelHandlerContext ctx, IdleStateEvent event) {
        if (logger.isDebugEnabled()) {
            SocketAddress remoteAddress = ctx.channel().remoteAddress();
            logger.info("{} idle timeout: {}", remoteAddress, event.state());
        }
        if (ctx.channel().isActive()) {
            ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
