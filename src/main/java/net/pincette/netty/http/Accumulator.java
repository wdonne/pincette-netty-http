package net.pincette.netty.http;

import static net.pincette.util.Util.rethrow;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Accumulates a request and when that's done it calls the request handler.
 *
 * @author Werner Donn\u00e0
 * @since 1.0
 */
public class Accumulator implements Subscriber<ByteBuf> {
  private final List<ByteBuf> buffers = new ArrayList<>();
  private final CompletableFuture<Publisher<ByteBuf>> future = new CompletableFuture<>();
  private final HttpRequest request;
  private final RequestHandlerAccumulated requestHandler;
  private final HttpResponse response;
  private Subscription subscription;

  public Accumulator(
      final HttpRequest request,
      final HttpResponse response,
      final RequestHandlerAccumulated requestHandler) {
    this.request = request;
    this.response = response;
    this.requestHandler = requestHandler;
  }

  public CompletionStage<Publisher<ByteBuf>> get() {
    return future;
  }

  public void onComplete() {
    requestHandler
        .apply(request, new ByteBufInputStream(buffers), response)
        .thenAccept(future::complete);
  }

  public void onError(final Throwable t) {
    rethrow(t);
  }

  public void onNext(final ByteBuf buffer) {
    buffers.add(buffer);
    subscription.request(1);
  }

  public void onSubscribe(final Subscription subscription) {
    this.subscription = subscription;
    subscription.request(1);
  }
}
