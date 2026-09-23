/**
 * Code whose effect lands at the client: sending on its WebSocket session, closing it.
 * Counterpart of {@link io.github.pdkovacs.wsgw.appward}.
 *
 * <p>Unlike {@code appward}, this package holds only the interfaces, which the route handlers use
 * to act on the client when the app asks them to. They are implemented by
 * {@link io.github.pdkovacs.wsgw.socket.WsConnections}. Code with no outward effect, such as
 * registry bookkeeping and the session handover on open, belongs in {@code socket} instead.
 */
package io.github.pdkovacs.wsgw.clientward;
