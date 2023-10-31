package net.pincette.netty.http;

import static java.util.Optional.ofNullable;

import io.netty.handler.codec.http.HttpRequest;
import java.util.function.Predicate;

public class Dispatcher {
  private final RequestHandler handler;

  private Dispatcher(final RequestHandler handler) {
    this.handler = handler;
  }

  private static RequestHandler compose(
      final RequestHandler handler1, final RequestHandler handler2) {
    return (request, requestBody, response) ->
        ofNullable(handler1.apply(request, requestBody, response))
            .orElseGet(() -> handler2.apply(request, requestBody, response));
  }

  private static RequestHandler createHandler(
      final Predicate<HttpRequest> condition, final RequestHandler handler) {
    return (request, requestBody, response) ->
        condition.test(request) ? handler.apply(request, requestBody, response) : null;
  }

  public static Dispatcher when(
      final Predicate<HttpRequest> condition, final RequestHandler handler) {
    return new Dispatcher(createHandler(condition, handler));
  }

  public Dispatcher or(final Predicate<HttpRequest> condition, final RequestHandler handler) {
    return new Dispatcher(compose(this.handler, createHandler(condition, handler)));
  }

  public RequestHandler orElse(final RequestHandler handler) {
    return compose(this.handler, handler);
  }
}
