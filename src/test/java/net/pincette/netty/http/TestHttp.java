package net.pincette.netty.http;

import static io.netty.buffer.Unpooled.wrappedBuffer;
import static io.netty.handler.codec.http.HttpHeaderNames.LOCATION;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpMethod.HEAD;
import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.handler.codec.http.HttpResponseStatus.MOVED_PERMANENTLY;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.nio.channels.Channels.newChannel;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.pincette.io.StreamConnector.copy;
import static net.pincette.netty.http.TestUtil.resourceHandler;
import static net.pincette.netty.http.Util.simpleResponse;
import static net.pincette.rs.Chain.with;
import static net.pincette.rs.LambdaSubscriber.lambdaSubscriber;
import static net.pincette.rs.ReadableByteChannelPublisher.readableByteChannel;
import static net.pincette.util.Collections.list;
import static net.pincette.util.Pair.pair;
import static net.pincette.util.Util.tryToDoRethrow;
import static net.pincette.util.Util.tryToGetRethrow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.function.Supplier;
import net.pincette.function.SideEffect;
import net.pincette.io.DevNullInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TestHttp {
  private static final HttpClient httpClient = new HttpClient();
  private static HttpServer getServer;
  private static HttpServer postAccumulatedServer;
  private static HttpServer postStreamingServer;
  private static HttpServer redirectServer;
  private static final Map<String, byte[]> resources = new HashMap<>();

  private static String absUri(final String uri, final int port) {
    return "http://localhost:" + port + (!uri.startsWith("/") ? "/" : "") + uri;
  }

  @AfterAll
  static void afterAll() {
    getServer.close();
    redirectServer.close();
    postAccumulatedServer.close();
    postStreamingServer.close();
  }

  @BeforeAll
  static void beforeAll() {
    getServer = new HttpServer(9000, resourceHandler());
    redirectServer = new HttpServer(9001, TestHttp::redirectHandler);
    postAccumulatedServer = new HttpServer(9002, TestHttp::postHandlerAccumulated);
    postStreamingServer = new HttpServer(9003, TestHttp::postHandlerStreaming);
    getServer.run();
    redirectServer.run();
    postAccumulatedServer.run();
    postStreamingServer.run();
  }

  private static Subscriber<ByteBuf> catchResponse(final CompletableFuture<byte[]> future) {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream(0xfffff);

    return lambdaSubscriber(
        buffer -> bytes.write(toBytes(buffer)), () -> future.complete(bytes.toByteArray()));
  }

  private static boolean compareStreams(final InputStream in1, final InputStream in2) {
    final InputStream buffered1 = new BufferedInputStream(in1);
    final InputStream buffered2 = new BufferedInputStream(in2);
    int b1;
    int b2;

    try {
      while ((b1 = readByte(buffered1)) == (b2 = readByte(buffered2)) && b1 != -1)
        ;
    } finally {
      tryToDoRethrow(
          () -> {
            buffered1.close();
            buffered2.close();
          });
    }

    return b1 == -1 && b2 == -1;
  }

  private static void post(final int port) {
    for (int i = 0; i < 100; ++i) {
      list(
              pair("file.pdf", "application/pdf"),
              pair("text.txt", "text/plain"),
              pair("empty", "application-octet-stream"))
          .forEach(resource -> testPost(resource.first, resource.second, port));
    }
  }

  private static CompletionStage<Publisher<ByteBuf>> postHandlerAccumulated(
      final HttpRequest request, final InputStream requestBody, final HttpResponse response) {
    return simpleResponse(
        response,
        OK,
        request.headers().get("Content-Type"),
        with(readableByteChannel(newChannel(requestBody))).map(TestUtil::toNettyBuffer).get());
  }

  private static CompletionStage<Publisher<ByteBuf>> postHandlerStreaming(
      final HttpRequest request,
      final Publisher<ByteBuf> requestBody,
      final HttpResponse response) {
    return simpleResponse(
        response,
        OK,
        request.headers().get("Content-Type"),
        with(requestBody).map(ByteBuf::retain).get());
  }

  private static void postResource(
      final String resource, final String contentType, final int port) {
    final byte[] bytes = read(resource);
    final ResponseAccumulator accumulator = new ResponseAccumulator();

    final HttpResponse response =
        httpClient
            .request(
                new DefaultFullHttpRequest(
                    HTTP_1_1,
                    POST,
                    absUri("/", port),
                    wrappedBuffer(bytes),
                    new DefaultHttpHeaders()
                        .add("Content-Type", contentType)
                        .add("Content-Length", bytes.length),
                    EmptyHttpHeaders.INSTANCE),
                accumulator)
            .toCompletableFuture()
            .join();

    assertEquals(OK, response.status());
    assertTrue(compareStreams(new ByteArrayInputStream(bytes), accumulator.getBody()));
  }

  private static byte[] read(final String resource) {
    return resources.computeIfAbsent(
        resource,
        r ->
            read(
                () ->
                    tryToGetRethrow(() -> TestHttp.class.getResourceAsStream(r))
                        .orElseGet(DevNullInputStream::new)));
  }

  private static byte[] read(final Supplier<InputStream> in) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream(0xfffff);

    tryToDoRethrow(() -> copy(in.get(), out));

    return out.toByteArray();
  }

  private static int readByte(final InputStream in) {
    return tryToGetRethrow(in::read).orElse(-1);
  }

  private static CompletionStage<Publisher<ByteBuf>> redirectHandler(
      final HttpRequest request, final InputStream requestBody, final HttpResponse response) {
    response.setStatus(MOVED_PERMANENTLY);
    response
        .headers()
        .add(
            LOCATION,
            absUri(
                Objects.requireNonNull(
                    tryToGetRethrow(() -> new URI(request.uri()).getPath()).orElse(null)),
                9000));

    return completedFuture(null);
  }

  private static CompletionStage<byte[]> requestResource(
      final String uri, final HttpResponseStatus expected) {
    final CompletableFuture<byte[]> future = new CompletableFuture<>();

    return httpClient
        .request(new DefaultFullHttpRequest(HTTP_1_1, GET, uri), catchResponse(future))
        .thenApply(
            response ->
                SideEffect.<HttpResponse>run(() -> assertEquals(expected, response.status()))
                    .andThenGet(() -> response))
        .thenComposeAsync(response -> future);
  }

  private static void testGet(final String resource, final HttpResponseStatus expected) {
    requestResource(absUri(resource, 9000), expected)
        .thenAccept(res -> assertArrayEquals(read("/" + resource), res))
        .toCompletableFuture()
        .join();
  }

  private static void testHead(final String resource, final HttpResponseStatus expected) {
    httpClient
        .request(new DefaultFullHttpRequest(HTTP_1_1, HEAD, absUri(resource, 9000)))
        .thenAccept(res -> assertEquals(expected, res.status()))
        .toCompletableFuture()
        .join();
  }

  private static void testPost(final String resource, final String contentType, final int port) {
    postResource("/" + resource, contentType, port);
  }

  private static void testRedirect(final String resource) {
    requestResource(absUri(resource, 9001), OK)
        .thenAccept(res -> assertArrayEquals(read("/" + resource), res))
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
  @DisplayName("post accumulated")
  void postAccumulated() {
    post(9002);
  }

  @Test
  @DisplayName("post streaming")
  void postStreaming() {
    post(9003);
  }

  @Test
  @DisplayName("redirect")
  void redirect() {
    for (int i = 0; i < 10; ++i) {
      list("file.pdf", "text.txt", "empty").forEach(TestHttp::testRedirect);
    }
  }
}
