/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.undertow.server;

import io.undertow.server.handlers.HttpHandlers;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.util.ChunkedStreamSinkChannel;
import io.undertow.util.ChunkedStreamSourceChannel;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import org.xnio.ChannelListener;
import org.xnio.ChannelListeners;
import org.xnio.channels.EmptyStreamSourceChannel;
import org.xnio.channels.FixedLengthStreamSinkChannel;
import org.xnio.channels.FixedLengthStreamSourceChannel;
import org.xnio.channels.PushBackStreamChannel;
import org.xnio.channels.StreamSinkChannel;
import org.xnio.channels.StreamSourceChannel;

/**
 * Handler responsible for dealing with wrapping the response stream and request stream to deal with persistent
 * connections and transfer encodings.
 * <p/>
 * This should generally be the first handler in any handler chain, as without it persistent connections will not work.
 * <p/>
 * Installing this handler after any other handler that wraps the channel will generally result in broken behaviour,
 * as chunked encoding must be the last transformation applied.
 *
 * @author Stuart Douglas
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @see http://tools.ietf.org/html/rfc2616#section-4.4
 */
public class HttpTransferEncodingHandler implements HttpHandler {

    private volatile HttpHandler next = ResponseCodeHandler.HANDLE_404;

    /**
     * Construct a new instance.
     */
    public HttpTransferEncodingHandler() {
    }

    /**
     * Construct a new instance.
     *
     * @param next the next HTTP handler
     */
    public HttpTransferEncodingHandler(final HttpHandler next) {
        this.next = next;
    }

    @Override
    public void handleRequest(final HttpServerExchange exchange, final HttpCompletionHandler completionHandler) {
        final HeaderMap requestHeaders = exchange.getRequestHeaders();
        boolean persistentConnection;
        if (exchange.isHttp11()) {
            persistentConnection = !(requestHeaders.contains(Headers.CONNECTION) && requestHeaders.getFirst(Headers.CONNECTION).equalsIgnoreCase(Headers.CLOSE));
        } else if (exchange.isHttp10()) {
            persistentConnection = requestHeaders.contains(Headers.CONNECTION) && requestHeaders.getFirst(Headers.CONNECTION).equalsIgnoreCase(Headers.KEEP_ALIVE);
        } else {
            persistentConnection = false;
        }
        String transferEncoding = Headers.IDENTITY;
        final boolean hasTransferEncoding = requestHeaders.contains(Headers.TRANSFER_ENCODING);
        final boolean hasContentLength = requestHeaders.contains(Headers.CONTENT_LENGTH);
        if (hasTransferEncoding) {
            transferEncoding = requestHeaders.getLast(Headers.TRANSFER_ENCODING);
        }
        if (!transferEncoding.equalsIgnoreCase(Headers.IDENTITY)) {
            exchange.addRequestWrapper(chunkedStreamSourceChannelWrapper());
        } else if (hasContentLength) {
            final long contentLength = Long.parseLong(requestHeaders.get(Headers.CONTENT_LENGTH).getFirst());
            if (contentLength == 0L) {
                // no content - immediately start the next request, returning an empty stream for this one
                exchange.terminateRequest();
                exchange.addRequestWrapper(emptyStreamSourceChannelWrapper());
            } else {
                // fixed-length content - add a wrapper for a fixed-length stream
                if (persistentConnection) {
                    // but only if the connection is persistent; else why bother?
                    exchange.addRequestWrapper(fixedLengthStreamSourceChannelWrapper(contentLength));
                }
            }
        } else if (hasTransferEncoding) {
            if (transferEncoding.equalsIgnoreCase(Headers.IDENTITY)) {
                // make it not persistent
                persistentConnection = false;
            }
        } else if (persistentConnection) {
            // no content - immediately start the next request, returning an empty stream for this one
            exchange.terminateRequest();
            exchange.addRequestWrapper(emptyStreamSourceChannelWrapper());
        }

        //now the response wrapper, to add in the appropriate connection control headers
        exchange.addResponseWrapper(responseWrapper(persistentConnection));
        HttpHandlers.executeHandler(next, exchange, completionHandler);
    }

    private ChannelWrapper<StreamSourceChannel> chunkedStreamSourceChannelWrapper() {
        return new ChannelWrapper<StreamSourceChannel>() {
            public StreamSourceChannel wrap(final StreamSourceChannel channel, final HttpServerExchange exchange) {
                return new ChunkedStreamSourceChannel((PushBackStreamChannel) channel, chunkedDrainListener(channel, exchange), exchange.getConnection().getBufferPool());
            }
        };
    }

    private static ChannelWrapper<StreamSinkChannel> responseWrapper(final boolean requestLooksPersistent) {
        return new ChannelWrapper<StreamSinkChannel>() {
            public StreamSinkChannel wrap(final StreamSinkChannel channel, final HttpServerExchange exchange) {
                final HeaderMap responseHeaders = exchange.getResponseHeaders();
                // test to see if we're still persistent
                boolean stillPersistent = requestLooksPersistent;
                String transferEncoding = Headers.IDENTITY;
                if (responseHeaders.contains(Headers.TRANSFER_ENCODING)) {
                    if (exchange.isHttp11()) {
                        transferEncoding = responseHeaders.getLast(Headers.TRANSFER_ENCODING);
                    } else {
                        // RFC 2616 3.6 last paragraph
                        responseHeaders.remove(Headers.TRANSFER_ENCODING);
                    }
                } else if (exchange.isHttp11() && !responseHeaders.contains(Headers.CONTENT_LENGTH)) {
                    //if we have a HTTP 1.1 request with no transfer encoding and no content length
                    //then we default to chunked, to enable persistent connections to work
                    responseHeaders.put(Headers.TRANSFER_ENCODING, Headers.CHUNKED);
                    transferEncoding = Headers.CHUNKED;
                }
                StreamSinkChannel wrappedChannel;
                final int code = exchange.getResponseCode();
                if (exchange.getRequestMethod().equalsIgnoreCase(Methods.HEAD) || (100 <= code && code <= 199) || code == 204 || code == 304) {
                    final ChannelListener<StreamSinkChannel> finishListener = stillPersistent ? terminateResponseListener(exchange) : null;
                    wrappedChannel = new FixedLengthStreamSinkChannel(channel, 0L, false, !stillPersistent, finishListener, this);
                } else if (!transferEncoding.equalsIgnoreCase("identity")) {
                    final ChannelListener<StreamSinkChannel> finishListener = stillPersistent ? terminateResponseListener(exchange) : null;
                    wrappedChannel = new ChunkedStreamSinkChannel(channel, false, !stillPersistent, finishListener, exchange.getConnection().getBufferPool());
                } else if (responseHeaders.contains(Headers.CONTENT_LENGTH)) {
                    final long contentLength = Long.parseLong(responseHeaders.get(Headers.CONTENT_LENGTH).getFirst());
                    final ChannelListener<StreamSinkChannel> finishListener = stillPersistent ? terminateResponseListener(exchange) : null;
                    // fixed-length response
                    wrappedChannel = new FixedLengthStreamSinkChannel(channel, contentLength, false, !stillPersistent, finishListener, this);
                } else {
                    // make it not persistent - very unfortunate for the next request handler really...
                    stillPersistent = false;
                    wrappedChannel = new FinishableStreamSinkChannel(channel, terminateResponseListener(exchange));
                }
                if (exchange.isHttp11()) {
                    if (stillPersistent) {
                        responseHeaders.remove(Headers.CONNECTION);
                    } else {
                        responseHeaders.put(Headers.CONNECTION, Headers.CLOSE);
                    }
                } else if (exchange.isHttp10()) {
                    if (stillPersistent) {
                        responseHeaders.put(Headers.CONNECTION, Headers.KEEP_ALIVE);
                    } else {
                        responseHeaders.remove(Headers.CONNECTION);
                    }
                }
                return wrappedChannel;
            }
        };
    }

    private static ChannelWrapper<StreamSourceChannel> fixedLengthStreamSourceChannelWrapper(final long contentLength) {
        return new ChannelWrapper<StreamSourceChannel>() {
            public StreamSourceChannel wrap(final StreamSourceChannel channel, final HttpServerExchange exchange) {
                return new FixedLengthStreamSourceChannel(channel, contentLength, false, fixedLengthDrainListener(channel, exchange), this);
            }
        };
    }

    private static ChannelWrapper<StreamSourceChannel> emptyStreamSourceChannelWrapper() {
        return new ChannelWrapper<StreamSourceChannel>() {
            public StreamSourceChannel wrap(final StreamSourceChannel channel, final HttpServerExchange exchange) {
                return new EmptyStreamSourceChannel(channel.getWorker(), channel.getReadThread());
            }
        };
    }

    private static ChannelListener<FixedLengthStreamSourceChannel> fixedLengthDrainListener(final StreamSourceChannel channel, final HttpServerExchange exchange) {
        return new ChannelListener<FixedLengthStreamSourceChannel>() {
            public void handleEvent(final FixedLengthStreamSourceChannel fixedLengthChannel) {
                long remaining = fixedLengthChannel.getRemaining();
                if (remaining > 0L) {
                    // keep it simple - set up an async drain
                    channel.getReadSetter().set(ChannelListeners.drainListener(remaining, terminateRequestListener(exchange), ChannelListeners.closingChannelExceptionHandler()));
                    channel.resumeReads();
                    return;
                }
                exchange.terminateRequest();
            }
        };
    }

    private static ChannelListener<ChunkedStreamSourceChannel> chunkedDrainListener(final StreamSourceChannel channel, final HttpServerExchange exchange) {
        return new ChannelListener<ChunkedStreamSourceChannel>() {
            public void handleEvent(final ChunkedStreamSourceChannel chunkedStreamSourceChannel) {
                channel.getReadSetter().set(ChannelListeners.drainListener(Long.MAX_VALUE, terminateRequestListener(exchange), ChannelListeners.closingChannelExceptionHandler()));
            }
        };
    }

    private static ChannelListener<StreamSinkChannel> terminateResponseListener(final HttpServerExchange exchange) {
        return new ChannelListener<StreamSinkChannel>() {
            public void handleEvent(final StreamSinkChannel channel) {
                exchange.terminateResponse();
            }
        };
    }

    private static ChannelListener<StreamSourceChannel> terminateRequestListener(final HttpServerExchange exchange) {
        return new ChannelListener<StreamSourceChannel>() {
            public void handleEvent(final StreamSourceChannel channel) {
                exchange.terminateRequest();
            }
        };
    }

    /**
     * Get the next HTTP handler.
     *
     * @return the next HTTP handler
     */
    public HttpHandler getNext() {
        return next;
    }

    /**
     * Set the next http handler.
     *
     * @param next the next http handler
     */
    public void setNext(final HttpHandler next) {
        HttpHandlers.handlerNotNull(next);
        this.next = next;
    }
}
