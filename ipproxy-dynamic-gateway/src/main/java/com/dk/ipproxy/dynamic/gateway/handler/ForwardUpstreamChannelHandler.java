package com.dk.ipproxy.dynamic.gateway.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ForwardUpstreamChannelHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ForwardUpstreamChannelHandler.class.getName());
    private final Channel peerChannel;

    public ForwardUpstreamChannelHandler(Channel peerChannel) {
        this.peerChannel = peerChannel;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (logger.isDebugEnabled()) {
            logger.debug("up {} forward to [{} -> {}]", ctx.channel().remoteAddress(), peerChannel.localAddress(), peerChannel.remoteAddress());
        }
        peerChannel.write(msg).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                closeAfterFlush(peerChannel);
            }
        });
        if (!peerChannel.isWritable()) {
            ctx.channel().config().setAutoRead(false);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        peerChannel.flush();
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        updatePeerAutoRead(ctx.channel().isWritable());
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (logger.isWarnEnabled()) {
            Channel channel = ctx.channel();
            logger.warn(String.format("channel %s <-> %s thrown %s", channel.localAddress(), channel.remoteAddress(), cause.getMessage()));
        }
        if (logger.isDebugEnabled()) {
            Channel channel = ctx.channel();
            logger.debug(String.format("channel %s <-> %s thrown", channel.localAddress(), channel.remoteAddress()), cause);
        }
        closeAfterFlush(peerChannel);
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        closeAfterFlush(peerChannel);
        ctx.fireChannelInactive();
    }

    private void updatePeerAutoRead(boolean autoRead) {
        if (!peerChannel.isActive()) {
            return;
        }
        Runnable task = () -> {
            peerChannel.config().setAutoRead(autoRead);
            if (autoRead) {
                peerChannel.read();
            }
        };
        if (peerChannel.eventLoop().inEventLoop()) {
            task.run();
        } else {
            peerChannel.eventLoop().execute(task);
        }
    }

    private void closeAfterFlush(Channel channel) {
        if (channel.isActive()) {
            channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
