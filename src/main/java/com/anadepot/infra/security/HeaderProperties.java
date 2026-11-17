package com.anadepot.infra.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code ana.headers.hsts} ({@code ANA_HSTS_ENABLED}): send {@code Strict-Transport-Security} on every response. Off by default because HSTS is only meaningful
 * (and only honoured by browsers) over HTTPS, and behind a TLS-terminating proxy this application sees plain HTTP, so Spring's own "only on secure requests"
 * rule would never fire. Turn it on in production, where the public URL is HTTPS-only.
 */
@ConfigurationProperties(prefix = "ana.headers")
public record HeaderProperties(@DefaultValue("false") boolean hsts) {
}
