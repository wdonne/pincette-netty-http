package net.pincette.netty.http;

import static io.netty.handler.codec.http.HttpHeaderNames.AUTHORIZATION;
import static io.netty.handler.codec.http.HttpHeaderNames.COOKIE;
import static java.net.URLDecoder.decode;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;
import static net.pincette.util.Or.tryWith;
import static net.pincette.util.Util.tryToGet;
import static net.pincette.util.Util.tryToGetSilent;

import com.auth0.jwt.interfaces.DecodedJWT;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.pincette.jwt.Verifier;

/**
 * A header handler thar verifies JWTs. A JWT can be a bearer token or in the <code>access_token
 * </code> cookie. When they are not valid it sets the status code of the response to 401. When they
 * are valid is sets the extension header <code>X-JWTPayload</code> to the stringified decoded JSON
 * payload of the JWT.
 *
 * @author Werner Donné
 * @since 3.1
 */
public class JWTVerifier {
  private static final String ACCESS_TOKEN = "access_token";
  static final String PAYLOAD_HEADER = "X-JWTPayload";

  private JWTVerifier() {}

  private static Map<String, String> cookies(final Stream<String> values) {
    return values
        .flatMap(value -> stream(value.split(";")))
        .map(cookie -> cookie.trim().split("="))
        .filter(split -> split.length == 2)
        .collect(toMap(s -> s[0], s -> s[1]));
  }

  private static Optional<String> getBearerToken(final HttpRequest request) {
    return tryWith(() -> getBearerTokenFromAuthorization(request))
        .or(() -> getCookies(request).get(ACCESS_TOKEN))
        .get()
        .flatMap(t -> tryToGet(() -> decode(t, UTF_8)));
  }

  private static Map<String, String> getCookies(final HttpRequest request) {
    return Optional.of(request.headers())
        .map(h -> h.getAll(COOKIE))
        .map(values -> cookies(values.stream()))
        .orElseGet(Collections::emptyMap);
  }

  private static Optional<String> getBearerTokenFromAuthorization(final HttpRequest request) {
    return Optional.of(request.headers())
        .map(h -> h.get(AUTHORIZATION))
        .map(header -> header.split(" "))
        .filter(s -> s.length == 2)
        .filter(s -> s[0].equalsIgnoreCase("Bearer"))
        .map(s -> s[1]);
  }

  private static Headers unauthorized(final Headers headers) {
    return new Headers(
        headers.request(), headers.response().setStatus(HttpResponseStatus.UNAUTHORIZED));
  }

  /**
   * Creates a JWT verifier.
   *
   * @param publicKey the public key in PEM format. Only RSA and ECDSA keys are supported.
   * @return The header handler.
   */
  public static HeaderHandler verify(final String publicKey) {
    final Verifier verifier = new Verifier(publicKey);

    return headers ->
        getBearerToken(headers.request())
            .flatMap(token -> tryToGetSilent(() -> verifier.verify(token)))
            .flatMap(decoded -> decoded.map(DecodedJWT::getPayload))
            .map(payload -> withPayload(headers, payload))
            .orElseGet(() -> unauthorized(headers));
  }

  private static Headers withPayload(final Headers headers, final String payload) {
    headers.request().headers().set(PAYLOAD_HEADER, payload);

    return new Headers(headers.request(), headers.response());
  }
}
