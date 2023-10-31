package net.pincette.netty.http;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.pincette.rs.Util.cancel;
import static net.pincette.rs.Util.empty;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.util.Optional;

/**
 * This lets you chain header handlers in front of the actual request handler. When a header handler
 * sets the response status code to 400 or higher, the request is ended with that response.
 *
 * @author Werner Donné
 * @since 3.1
 */
public class PipelineHandler {
  private final HeaderHandler handler;

  private PipelineHandler(final HeaderHandler handler) {
    this.handler = handler;
  }

  private static HeaderHandler compose(final HeaderHandler handler1, final HeaderHandler handler2) {
    final HeaderHandler next =
        headers -> ok(headers.response()) ? handler2.apply(headers) : headers;

    return (HeaderHandler) handler1.andThen(next);
  }

  /**
   * Creates a pipeline handler with the first header handler.
   *
   * @param handler the given header handler.
   * @return A new pipeline handler.
   */
  public static PipelineHandler handle(final HeaderHandler handler) {
    return new PipelineHandler(handler);
  }

  private static boolean ok(final HttpResponse response) {
    return response.status() == null || response.status().code() < 400;
  }

  /**
   * Create the complete request handler.
   *
   * @param handler the given request handler that is appended to the end of the pipeline.
   * @return The request handler for the server.
   */
  public RequestHandler finishWith(final RequestHandler handler) {
    return (request, requestBody, response) ->
        runHandler(request, response)
            .map(h -> handler.apply(h.request(), requestBody, h.response()))
            .orElseGet(
                () -> {
                  cancel(requestBody);

                  return completedFuture(empty());
                });
  }

  /**
   * Create the complete request handler.
   *
   * @param handler the given request handler that is appended to the end of the pipeline.
   * @return The request handler for the server.
   */
  public RequestHandlerAccumulated finishWith(final RequestHandlerAccumulated handler) {
    return (request, requestBody, response) ->
        runHandler(request, response)
            .map(h -> handler.apply(h.request(), requestBody, h.response()))
            .orElseGet(() -> completedFuture(empty()));
  }

  /**
   * Appends a header handler to the pipeline handler.
   *
   * @param handler the given header handler.
   * @return A new pipeline handler.
   */
  public PipelineHandler then(final HeaderHandler handler) {
    return new PipelineHandler(compose(this.handler, handler));
  }

  private Optional<Headers> runHandler(final HttpRequest request, final HttpResponse response) {
    return Optional.of(this.handler.apply(new Headers(request, response)))
        .filter(h -> ok(h.response()));
  }
}
