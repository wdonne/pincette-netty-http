package net.pincette.netty.http;

import java.util.function.UnaryOperator;

/**
 * The function that defines a header handler. It can be used to inspect or manipulate headers.
 *
 * @author Werner Donné
 * @since 3.1
 */
public interface HeaderHandler extends UnaryOperator<Headers> {}
