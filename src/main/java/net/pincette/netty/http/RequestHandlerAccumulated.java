package net.pincette.netty.http;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.io.InputStream;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Publisher;

/**
 * The interface for handling accumulated requests.
 *
 * @author Werner Donn\u00e9
 * @since 1.0
 */
@FunctionalInterface
public interface RequestHandlerAccumulated {
  /**
   * An implementation should return a publisher for the response body. It should not block.
   *
   * @param request the request received from the server.
   * @param requestBody the accumulated request body.
   * @param response the response the server will send back.
   * @return The publisher through which the response body chunks are emitted. If the publisher is
   *     <code>null</code> then nothing is emitted.
   * @since 1.0
   */
  CompletionStage<Publisher<ByteBuf>> apply(
      HttpRequest request, InputStream requestBody, HttpResponse response);
}
