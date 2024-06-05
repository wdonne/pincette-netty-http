package net.pincette.netty.http;

import static java.net.http.HttpRequest.BodyPublishers.fromPublisher;
import static java.net.http.HttpResponse.BodyHandlers.ofPublisher;
import static net.pincette.rs.Chain.with;
import static net.pincette.util.Collections.set;
import static net.pincette.util.Util.tryToGetRethrow;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Set;
import java.util.concurrent.Flow.Publisher;
import java.util.stream.Stream;
import net.pincette.rs.FlattenList;

/**
 * A request handler that forwards everything to another URI, keeping the path, query and fragment
 * of the original request. The headers <code>Connection</code>, <code>Content-Length</code>, <code>
 * Expect</code>, <code>Host</code> and <code>Upgrade</code> are not forwarded.
 *
 * @author Werner Donné
 * @since 3.2.0
 */
public class Forwarder {
  private static final Set<String> DISALLOWED_HEADERS =
      set("connection", "content-length", "expect", "host", "upgrade");

  private Forwarder() {}

  private static java.net.http.HttpRequest createRequest(
      final URI uri, final HttpRequest request, final Publisher<ByteBuf> requestBody) {
    return java.net.http.HttpRequest.newBuilder()
        .uri(uri)
        .method(
            request.method().name(), fromPublisher(with(requestBody).map(ByteBuf::nioBuffer).get()))
        .headers(headers(request.headers()))
        .build();
  }

  /**
   * Create a forwarding request handler.
   *
   * @param uri the URI to forward to.
   * @param client the given HTTP client.
   * @return The request handler.
   */
  public static RequestHandler forwarder(final URI uri, final HttpClient client) {
    return (request, requestBody, response) ->
        client
            .sendAsync(
                createRequest(resolve(uri, request.uri()), request, requestBody), ofPublisher())
            .thenApply(
                resp -> {
                  setResponse(response, resp.headers(), resp.statusCode());

                  return resp;
                })
            .thenApply(
                resp ->
                    with(resp.body()).map(new FlattenList<>()).map(Unpooled::wrappedBuffer).get());
  }

  private static String[] headers(final HttpHeaders headers) {
    return headers.entries().stream()
        .filter(e -> !DISALLOWED_HEADERS.contains(e.getKey().toLowerCase()))
        .flatMap(e -> Stream.of(e.getKey(), e.getValue()))
        .toArray(String[]::new);
  }

  private static URI resolve(final URI configured, final String given) {
    return tryToGetRethrow(() -> new URI(given))
        .flatMap(
            g ->
                tryToGetRethrow(
                    () ->
                        new URI(
                            configured.getScheme(),
                            configured.getUserInfo(),
                            configured.getHost(),
                            configured.getPort(),
                            g.getPath(),
                            g.getQuery(),
                            g.getFragment())))
        .orElse(null);
  }

  private static void setHeaders(final HttpHeaders target, final java.net.http.HttpHeaders source) {
    target.clear();
    source.map().forEach(target::set);
  }

  private static void setResponse(
      final HttpResponse response, final java.net.http.HttpHeaders headers, final int statusCode) {
    setHeaders(response.headers(), headers);

    if (statusCode != -1) {
      response.setStatus(HttpResponseStatus.valueOf(statusCode));
    }
  }
}
