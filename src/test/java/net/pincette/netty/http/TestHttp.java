package net.pincette.netty.http;

import static io.netty.handler.codec.http.HttpHeaderNames.LOCATION;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpMethod.HEAD;
import static io.netty.handler.codec.http.HttpResponseStatus.MOVED_PERMANENTLY;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.util.Arrays.compare;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.pincette.io.StreamConnector.copy;
import static net.pincette.netty.http.TestUtil.resourceHandler;
import static net.pincette.rs.LambdaSubscriber.lambdaSubscriber;
import static net.pincette.util.Collections.list;
import static net.pincette.util.Util.tryToDoWithRethrow;
import static net.pincette.util.Util.tryToGetRethrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Publisher;
import net.pincette.function.SideEffect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TestHttp {
  private static final HttpClient httpClient = new HttpClient();
  private static HttpServer getServer;
  private static HttpServer redirectServer;

  private static String absUri(final String uri) {
    return "http://localhost:9000/" + uri;
  }

  @AfterAll
  static void afterAll() {
    getServer.close();
    redirectServer.close();
  }

  @BeforeAll
  static void beforeAll() {
    getServer = new HttpServer(9000, resourceHandler());
    redirectServer = new HttpServer(9001, TestHttp::redirectHandler);
    getServer.run();
    redirectServer.run();
  }

  private static byte[] getResource(final String resource) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();

    ofNullable(TestHttp.class.getResourceAsStream(resource))
        .ifPresent(in -> tryToDoWithRethrow(() -> in, i -> copy(i, out)));

    return out.toByteArray();
  }

  private static CompletionStage<Publisher<ByteBuf>> redirectHandler(
      final HttpRequest request, final InputStream requestBody, final HttpResponse response) {
    response.setStatus(MOVED_PERMANENTLY);
    response
        .headers()
        .add(
            LOCATION, absUri(tryToGetRethrow(() -> new URI(request.uri()).getPath()).orElse(null)));

    return completedFuture(null);
  }

  private static CompletionStage<byte[]> requestResource(
      final String uri, final HttpResponseStatus expected) {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final CompletableFuture<byte[]> future = new CompletableFuture<>();

    return httpClient
        .request(
            new DefaultFullHttpRequest(HTTP_1_1, GET, uri),
            lambdaSubscriber(
                buffer -> bytes.write(toBytes(buffer)), () -> future.complete(bytes.toByteArray())))
        .thenApply(
            response ->
                SideEffect.<HttpResponse>run(() -> assertEquals(expected, response.status()))
                    .andThenGet(() -> response))
        .thenComposeAsync(response -> future);
  }

  private static void testGet(final String resource, final HttpResponseStatus expected) {
    requestResource(absUri(resource), expected)
        .thenAccept(res -> assertEquals(0, compare(res, getResource("/" + resource))))
        .toCompletableFuture()
        .join();
  }

  private static void testHead(final String resource, final HttpResponseStatus expected) {
    httpClient
        .request(new DefaultFullHttpRequest(HTTP_1_1, HEAD, absUri(resource)))
        .thenAccept(res -> assertEquals(expected, res.status()))
        .toCompletableFuture()
        .join();
  }

  private static void testRedirect(final String resource) {
    requestResource(absUri(resource), OK)
        .thenAccept(res -> assertEquals(0, compare(res, getResource("/" + resource))))
        .toCompletableFuture()
        .join();
  }

  private static byte[] toBytes(final ByteBuf buffer) {
    final byte[] bytes = new byte[buffer.readableBytes()];

    buffer.readBytes(bytes);

    return bytes;
  }

  @Test
  @DisplayName("get")
  void get() {
    for (int i = 0; i < 10; ++i) {
      list("file.pdf", "text.txt", "empty").forEach(resource -> testGet(resource, OK));
    }
  }

  @Test
  @DisplayName("head")
  void head() {
    for (int i = 0; i < 10; ++i) {
      list("file.pdf", "text.txt", "empty").forEach(resource -> testHead(resource, OK));
    }
  }

  @Test
  @DisplayName("not found")
  void notFound() {
    testGet("not_found", NOT_FOUND);
    testHead("not_found", NOT_FOUND);
  }

  @Test
  @DisplayName("redirect")
  void redirect() {
    for (int i = 0; i < 10; ++i) {
      list("file.pdf", "text.txt", "empty").forEach(TestHttp::testRedirect);
    }
  }
}
