package net.pincette.netty.http;

import static java.util.stream.Collectors.toList;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import org.reactivestreams.Publisher;

/**
 * Accumulates a request and when that's done it calls the request handler.
 *
 * @author Werner Donn\u00e0
 * @since 1.0
 */
@SuppressWarnings("java:S2176") // It was already published with that name.
public class Accumulator extends net.pincette.rs.Accumulator<ByteBuf, Publisher<ByteBuf>> {
  public Accumulator(
      final HttpRequest request,
      final HttpResponse response,
      final RequestHandlerAccumulated requestHandler) {
    super(
        buffers ->
            requestHandler.apply(
                request, new ByteBufInputStream(buffers.collect(toList())), response));
  }
}
