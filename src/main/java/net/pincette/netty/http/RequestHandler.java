package net.pincette.netty.http;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Publisher;

/**
 * The interface for handling requests.
 *
 * @author Werner Donn\u00e9
 * @since 1.0
 */
@FunctionalInterface
public interface RequestHandler {
  /**
   * An implementation should return a publisher for the response body. It should not block.
   *
   * @param request the request received from the server.
   * @param requestBody the publisher through which request body chunks are emitted. The buffers are
   *     released as soon as the <code>onNext</code> method of the subscriber returns. If you still
   *     need them later, you have to make a copy.
   * @param response the response the server will send back.
   * @return The publisher through which the response body chunks are emitted. If the publisher is
   *     <code>null</code> then nothing is emitted.
   * @since 1.0
   */
  CompletionStage<Publisher<ByteBuf>> apply(
      HttpRequest request, Publisher<ByteBuf> requestBody, HttpResponse response);
}
