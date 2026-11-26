package ec.yawar.gateway.ratelimit;

import java.net.InetSocketAddress;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.web.server.ServerWebExchange;

/**
 * Client address for rate limiting: the last {@code X-Forwarded-For} entry (trusted index 1), otherwise the TCP peer.
 * This is only reliable when exactly one trusted proxy (the Ingress, configured to append the real client address:
 * ingress-nginx {@code use-forwarded-headers} or the proxy protocol) sits in front. Without a proxy (docker compose)
 * the header is chosen by the client, so the key can be spoofed there.
 */
final class ClientAddress {

    private static final XForwardedRemoteAddressResolver RESOLVER = XForwardedRemoteAddressResolver.maxTrustedIndex(1);

    private ClientAddress() {
    }

    static String of(ServerWebExchange exchange) {
        InetSocketAddress address = RESOLVER.resolve(exchange);
        if (address == null) {
            return "unknown";
        }
        return address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString();
    }
}
