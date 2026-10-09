package dev.regpack.ports.connector;

/**
 * The one connector SPI. Sealed: the permitted kinds are the source kind, the destination kind
 * ({@link Transport}) and the lookup kind ({@link EnrichmentSource}).
 */
public sealed interface Connector permits SourceConnector, Transport, EnrichmentSource {
  ConnectorSpec spec();

  CheckResult check();
}
