package net.pincette.netty.http;

import static io.netty.handler.codec.http.HttpHeaderNames.AUTHORIZATION;
import static io.netty.handler.codec.http.HttpResponseStatus.UNAUTHORIZED;
import static net.pincette.netty.http.Util.BEARER;
import static net.pincette.netty.http.Util.getBearerToken;
import static net.pincette.util.Util.tryToGetSilent;

import net.pincette.jwt.Verifier;

/**
 * A header handler that verifies JWTs. A JWT can be a bearer token or in the <code>access_token
 * </code> cookie. When they are not valid it sets the status code of the response to 401. When they
 * are valid is sets the bearer token in the headers.
 *
 * @author Werner Donné
 * @since 3.1
 */
public class JWTVerifier {
  private JWTVerifier() {}

  private static Headers unauthorized(final Headers headers) {
    return new Headers(headers.request(), headers.response().setStatus(UNAUTHORIZED));
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
            .flatMap(token -> tryToGetSilent(() -> verifier.verify(token)).map(d -> token))
            .map(
                token -> {
                  headers.request().headers().set(AUTHORIZATION, BEARER + " " + token);
                  return headers;
                })
            .orElseGet(() -> unauthorized(headers));
  }
}
