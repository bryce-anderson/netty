/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.CharsetUtil;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;

public class HttpServerCodecTest {

    /**
     * Testcase for https://github.com/netty/netty/issues/433
     */
    @Test
    public void testUnfinishedChunkedHttpRequestIsLastFlag() throws Exception {

        int maxChunkSize = 2000;
        HttpServerCodec httpServerCodec = new HttpServerCodec(1000, 1000, maxChunkSize);
        EmbeddedChannel decoderEmbedder = new EmbeddedChannel(httpServerCodec);

        int totalContentLength = maxChunkSize * 5;
        decoderEmbedder.writeInbound(Unpooled.copiedBuffer(
                "PUT /test HTTP/1.1\r\n" +
                "Content-Length: " + totalContentLength + "\r\n" +
                "\r\n", CharsetUtil.UTF_8));

        int offeredContentLength = (int) (maxChunkSize * 2.5);
        decoderEmbedder.writeInbound(prepareDataChunk(offeredContentLength));
        decoderEmbedder.finish();

        HttpMessage httpMessage = decoderEmbedder.readInbound();
        assertNotNull(httpMessage);

        boolean empty = true;
        int totalBytesPolled = 0;
        for (;;) {
            HttpContent httpChunk = decoderEmbedder.readInbound();
            if (httpChunk == null) {
                break;
            }
            empty = false;
            totalBytesPolled += httpChunk.content().readableBytes();
            assertFalse(httpChunk instanceof LastHttpContent);
            httpChunk.release();
        }
        assertFalse(empty);
        assertEquals(offeredContentLength, totalBytesPolled);
    }

    void transfer(EmbeddedChannel in, EmbeddedChannel out, String msgHeader) {
        System.out.println(msgHeader);
        for (Object msg: in.outboundMessages()) {
            assertTrue(msg instanceof ByteBuf);
            ByteBuf buf = (ByteBuf)msg;
            String str = buf.toString(java.nio.charset.StandardCharsets.UTF_8);
            str = str.replace("\n", "\\n\n").replace("\r", "\\r");
            System.out.print(str);
            out.writeInbound(msg);
            out.checkException();
        }
        System.out.println("------------");
    }

    // Using DefaultFullHttpResponse will trigger the terminating '0\r\n\r\n\r\n' to be written, which is a protocol error
    @Test
    public void testChunkedEncodedHeadResponse() throws Exception {
        EmbeddedChannel serverEmbedder = new EmbeddedChannel(new HttpServerCodec());
        EmbeddedChannel clientEmbedder = new EmbeddedChannel(new HttpClientCodec());

        // Need to writing a HEAD request will prime the client codec
        HttpMessage firstRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.HEAD, "/foo");
        // if the following was used instead of a full response it results in IllegalStateException: unexpected message type: DefaultFullHttpResponse
        // unless its followed by a LastHttpContent, which is what causes the encoding of '0\r\n\r\n\r\n'
        // HttpMessage firstRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.HEAD, "/foo");
        clientEmbedder.writeOutbound(firstRequest);

        // Next write a GET request
        HttpMessage secondRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/foo");
        clientEmbedder.writeOutbound(secondRequest);

        transfer(clientEmbedder, serverEmbedder, "client->server");

        // Sever will try to send a chunked response to the HEAD request
        // The problem is that this will always send '0\r\n\r\n\r\n', which is illegal for a HEAD request
        HttpMessage firstMessage = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        HttpUtil.setTransferEncodingChunked(firstMessage, true);

        // Need to send a second response or the client codec won't have enough bytes to attempt decoding
        HttpMessage secondMessage = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        HttpUtil.setTransferEncodingChunked(secondMessage, true);

        assertTrue(serverEmbedder.writeOutbound(firstMessage, secondMessage));
        transfer(serverEmbedder, clientEmbedder, "server->client");

        for (Object msg: clientEmbedder.inboundMessages()) {
            if (msg instanceof HttpResponse) {
                HttpResponse resp = (HttpResponse) msg;
                System.out.println(resp);
                // The second message will fail with 'java.lang.IllegalArgumentException: invalid version format: 0'
                // Because the decoder assumed no body for the response to the HEAD request, but the
                // server codec sent '0\r\n\r\n\r\n', wich was interpreted as the status line of the next response.
                // There is no way to avoid encoding the chunked termination string '0\r\n\r\n\r\n' in netty4
                if (!resp.decoderResult().isSuccess()) {
                    fail("Failed to decode: " + resp);
                }
            }
            else if (msg instanceof HttpContent) {
                assertEquals(((HttpContent) msg).content().capacity(), 0);
            }
            else {
                fail("Unexpected message type: " + msg);
            }
        }
    }

    @Test
    public void test100Continue() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel(new HttpServerCodec(), new HttpObjectAggregator(1024));

        // Send the request headers.
        ch.writeInbound(Unpooled.copiedBuffer(
                "PUT /upload-large HTTP/1.1\r\n" +
                "Expect: 100-continue\r\n" +
                "Content-Length: 1\r\n\r\n", CharsetUtil.UTF_8));

        // Ensure the aggregator generates nothing.
        assertThat(ch.readInbound(), is(nullValue()));

        // Ensure the aggregator writes a 100 Continue response.
        ByteBuf continueResponse = ch.readOutbound();
        assertThat(continueResponse.toString(CharsetUtil.UTF_8), is("HTTP/1.1 100 Continue\r\n\r\n"));
        continueResponse.release();

        // But nothing more.
        assertThat(ch.readOutbound(), is(nullValue()));

        // Send the content of the request.
        ch.writeInbound(Unpooled.wrappedBuffer(new byte[] { 42 }));

        // Ensure the aggregator generates a full request.
        FullHttpRequest req = ch.readInbound();
        assertThat(req.headers().get(HttpHeaderNames.CONTENT_LENGTH), is("1"));
        assertThat(req.content().readableBytes(), is(1));
        assertThat(req.content().readByte(), is((byte) 42));
        req.release();

        // But nothing more.
        assertThat(ch.readInbound(), is(nullValue()));

        // Send the actual response.
        FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CREATED);
        res.content().writeBytes("OK".getBytes(CharsetUtil.UTF_8));
        res.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 2);
        ch.writeOutbound(res);

        // Ensure the encoder handles the response after handling 100 Continue.
        ByteBuf encodedRes = ch.readOutbound();
        assertThat(encodedRes.toString(CharsetUtil.UTF_8),
                   is("HTTP/1.1 201 Created\r\n" + HttpHeaderNames.CONTENT_LENGTH + ": 2\r\n\r\nOK"));
        encodedRes.release();

        ch.finish();
    }

    private static ByteBuf prepareDataChunk(int size) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < size; ++i) {
            sb.append('a');
        }
        return Unpooled.copiedBuffer(sb.toString(), CharsetUtil.UTF_8);
    }
}
