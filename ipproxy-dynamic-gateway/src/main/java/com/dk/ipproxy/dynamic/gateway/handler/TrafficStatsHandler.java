package com.dk.ipproxy.dynamic.gateway.handler;

import com.dk.ipproxy.dynamic.gateway.cache.TrafficCounter;
import com.dk.ipproxy.dynamic.gateway.context.ChannelContext;
import com.dk.ipproxy.dynamic.gateway.context.ContextAttrKey;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.apache.commons.lang3.math.NumberUtils;

/**
 * 流量统计
 */
@ChannelHandler.Sharable
public class TrafficStatsHandler extends ChannelDuplexHandler {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ChannelContext userChannelContext = ctx.channel().attr(ContextAttrKey.CHANNEL_FORWARD_CONTEXT).get();
        if (msg instanceof ByteBuf && userChannelContext != null) {
            // socks5协议
            long msgBytes = ((ByteBuf) msg).readableBytes();
            TrafficCounter.addSentBytes(userChannelContext.getUid(), msgBytes);
        } else if (msg instanceof FullHttpRequest && userChannelContext != null) {
            // http协议
            FullHttpRequest req = (FullHttpRequest) msg;
            long contentLength = NumberUtils.toLong(req.headers().get(HttpHeaderNames.CONTENT_LENGTH), 0L);
            long headerLength = req.headers().names().stream()
                            .mapToInt(name -> req.headers().getAll(name).stream()
                                    .mapToInt(value -> name.length() + value.length() + 2)
                                    .sum())
                    .sum() + 2;
            TrafficCounter.addSentBytes(userChannelContext.getUid(), contentLength + headerLength);
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        ChannelContext userChannelContext = ctx.channel().attr(ContextAttrKey.CHANNEL_FORWARD_CONTEXT).get();
        // socks5协议
        if (msg instanceof ByteBuf && userChannelContext != null) {
            long msgBytes = ((ByteBuf) msg).readableBytes();
            TrafficCounter.addReceivedBytes(userChannelContext.getUid(), msgBytes);
        } else if (msg instanceof FullHttpResponse && userChannelContext != null) {
            FullHttpResponse resp = (FullHttpResponse) msg;
            long contentLength = NumberUtils.toLong(resp.headers().get(HttpHeaderNames.CONTENT_LENGTH), 0L);
            long headerLength = resp.headers().names().stream()
                    .mapToInt(name -> resp.headers().getAll(name).stream()
                            .mapToInt(value -> name.length() + value.length() + 2)
                            .sum())
                    .sum() + 2;
            TrafficCounter.addReceivedBytes(userChannelContext.getUid(), contentLength + headerLength);
        }
        super.write(ctx, msg, promise);
    }
}