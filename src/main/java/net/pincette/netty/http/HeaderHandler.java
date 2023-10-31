package net.pincette.netty.http;

import java.util.function.UnaryOperator;

/**
 * The function that defines a header handler.
 *
 * @author Werner Donné
 * @since 3.1
 */
public interface HeaderHandler extends UnaryOperator<Headers> {}
