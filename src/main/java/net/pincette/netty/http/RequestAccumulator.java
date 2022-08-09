package net.pincette.netty.http;

import static net.pincette.netty.http.Util.asInputStream;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.util.concurrent.Flow.Publisher;
import net.pincette.rs.Accumulator;

/**
 * Accumulates a request and when that's done it calls the request handler.
 *
 * @author Werner Donn\u00e0
 * @since 1.0
 */
public class RequestAccumulator extends Accumulator<ByteBuf, Publisher<ByteBuf>> {
  public RequestAccumulator(
      final HttpRequest request,
      final HttpResponse response,
      final RequestHandlerAccumulated requestHandler) {
    super(buffers -> requestHandler.apply(request, asInputStream(buffers), response));
  }
}
