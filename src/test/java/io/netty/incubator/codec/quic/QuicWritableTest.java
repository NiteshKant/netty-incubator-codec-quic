/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.incubator.codec.quic;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.PromiseNotifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class QuicWritableTest extends AbstractQuicTest {

    @Test
    public void testCorrectlyHandleWritabilityReadRequestedInReadComplete() throws Throwable {
        testCorrectlyHandleWritability(true);
    }

    @Test
    public void testCorrectlyHandleWritabilityReadRequestedInRead() throws Throwable {
        testCorrectlyHandleWritability(false);
    }

    private static void testCorrectlyHandleWritability(boolean readInComplete) throws Throwable  {
        int bufferSize = 64 * 1024;
        Promise<Void> writePromise = ImmediateEventExecutor.INSTANCE.newPromise();
        final AtomicReference<Throwable> serverErrorRef = new AtomicReference<>();
        final AtomicReference<Throwable> clientErrorRef = new AtomicReference<>();
        Channel server = QuicTestUtils.newServer(
                QuicTestUtils.newQuicServerBuilder().initialMaxStreamsBidirectional(5000),
                InsecureQuicTokenHandler.INSTANCE,
                null, new ChannelInboundHandlerAdapter() {

                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ByteBuf buffer = (ByteBuf) msg;
                        buffer.release();
                        ctx.writeAndFlush(ctx.alloc().buffer(bufferSize).writeZero(bufferSize))
                                .addListener(new PromiseNotifier<>(writePromise));
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        serverErrorRef.set(cause);
                    }

                    @Override
                    public boolean isSharable() {
                        return true;
                    }
                });
        InetSocketAddress address = (InetSocketAddress) server.localAddress();
        Channel channel = QuicTestUtils.newClient(QuicTestUtils.newQuicClientBuilder()
                .initialMaxStreamDataBidirectionalLocal(bufferSize / 4));
        try {
            QuicChannel quicChannel = QuicChannel.newBootstrap(channel)
                    .handler(new ChannelInboundHandlerAdapter())
                    .streamHandler(new ChannelInboundHandlerAdapter())
                    .remoteAddress(address)
                    .connect()
                    .get();
            QuicStreamChannel stream = quicChannel.createStream(
                    QuicStreamType.BIDIRECTIONAL, new ChannelInboundHandlerAdapter() {
                        int bytes;

                        @Override
                        public void channelRegistered(ChannelHandlerContext ctx) {
                            ctx.channel().config().setAutoRead(false);
                        }

                        @Override
                        public void channelActive(ChannelHandlerContext ctx) {
                            ctx.writeAndFlush(ctx.alloc().buffer(8).writeLong(8));
                        }

                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            if (bytes == 0) {
                                // First read
                                assertFalse(writePromise.isDone());
                            }
                            ByteBuf buffer = (ByteBuf) msg;
                            bytes += buffer.readableBytes();
                            buffer.release();
                            if (bytes == bufferSize) {
                                ctx.close();
                                assertTrue(writePromise.isDone());
                            }

                            if (!readInComplete) {
                                ctx.read();
                            }
                        }

                        @Override
                        public void channelReadComplete(ChannelHandlerContext ctx) {
                            if (readInComplete) {
                                ctx.read();
                            }
                        }

                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                            clientErrorRef.set(cause);
                        }
                    }).get();
            assertFalse(writePromise.isDone());

            // Let's trigger the reads. This will ensure we will consume the data and the remote peer
            // should be notified that it can write more data.
            stream.read();

            writePromise.sync();
            stream.closeFuture().sync();
            quicChannel.close().sync();

            throwIfNotNull(serverErrorRef);
            throwIfNotNull(clientErrorRef);
        } finally {
            server.close().sync();
            // Close the parent Datagram channel as well.
            channel.close().sync();
        }
    }

    @Test
    @Timeout(value = 5000, unit = TimeUnit.MILLISECONDS)
    public void testBytesUntilUnwritable() throws Throwable  {
        Promise<Void> writePromise = ImmediateEventExecutor.INSTANCE.newPromise();
        final AtomicReference<Throwable> serverErrorRef = new AtomicReference<>();
        final AtomicReference<Throwable> clientErrorRef = new AtomicReference<>();
        final CountDownLatch writableAgainLatch = new CountDownLatch(1);
        int firstWriteNumBytes = 8;
        int maxData = 32 * 1024;
        final AtomicLong beforeWritableRef = new AtomicLong();
        Channel server = QuicTestUtils.newServer(
                QuicTestUtils.newQuicServerBuilder().initialMaxStreamsBidirectional(5000),
                InsecureQuicTokenHandler.INSTANCE,
                null, new ChannelInboundHandlerAdapter() {

                    private int numBytesRead;
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ByteBuf buffer = (ByteBuf) msg;
                        numBytesRead += buffer.readableBytes();
                        buffer.release();
                        if (numBytesRead == firstWriteNumBytes) {
                            long before = ctx.channel().bytesBeforeUnwritable();
                            beforeWritableRef.set(before);
                            assertTrue(before > 0);

                            while (before != 0) {
                                int size = (int) Math.min(before, 1024);
                                ctx.write(ctx.alloc().buffer(size).writeZero(size));
                                long newBefore = ctx.channel().bytesBeforeUnwritable();

                                assertEquals(before, newBefore + size);
                                before = newBefore;
                            }
                            ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(new PromiseNotifier<>(writePromise));
                        }
                    }

                    @Override
                    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
                        if (ctx.channel().isWritable()) {
                            if (ctx.channel().bytesBeforeUnwritable() > 0) {
                                writableAgainLatch.countDown();
                            }
                        }
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        serverErrorRef.set(cause);
                    }

                    @Override
                    public boolean isSharable() {
                        return true;
                    }
                });
        InetSocketAddress address = (InetSocketAddress) server.localAddress();
        Channel channel = QuicTestUtils.newClient(QuicTestUtils.newQuicClientBuilder()
                .initialMaxStreamDataBidirectionalLocal(maxData));
        try {
            QuicChannel quicChannel = QuicChannel.newBootstrap(channel)
                    .handler(new ChannelInboundHandlerAdapter())
                    .streamHandler(new ChannelInboundHandlerAdapter())
                    .remoteAddress(address)
                    .connect()
                    .get();
            QuicStreamChannel stream = quicChannel.createStream(
                    QuicStreamType.BIDIRECTIONAL, new ChannelInboundHandlerAdapter() {
                        int bytes;

                        @Override
                        public void channelRegistered(ChannelHandlerContext ctx) {
                            ctx.channel().config().setAutoRead(false);
                        }

                        @Override
                        public void channelActive(ChannelHandlerContext ctx) {
                            ctx.writeAndFlush(ctx.alloc().buffer(firstWriteNumBytes).writeZero(firstWriteNumBytes));
                        }

                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            ByteBuf buffer = (ByteBuf) msg;
                            bytes += buffer.readableBytes();
                            buffer.release();
                            if (bytes == beforeWritableRef.get()) {
                                assertTrue(writePromise.isDone());
                            }
                        }

                        @Override
                        public void channelReadComplete(ChannelHandlerContext ctx) {
                            ctx.read();
                        }

                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                            clientErrorRef.set(cause);
                        }
                    }).get();

            // Let's trigger the reads. This will ensure we will consume the data and the remote peer
            // should be notified that it can write more data.
            stream.read();

            writePromise.sync();
            writableAgainLatch.await();
            stream.close().sync();
            stream.closeFuture().sync();
            quicChannel.close().sync();

            throwIfNotNull(serverErrorRef);
            throwIfNotNull(clientErrorRef);
        } finally {
            server.close().sync();
            // Close the parent Datagram channel as well.
            channel.close().sync();
        }
    }

    private static void throwIfNotNull(AtomicReference<Throwable> errorRef) throws Throwable {
        Throwable cause = errorRef.get();
        if (cause != null) {
            throw cause;
        }
    }
}
