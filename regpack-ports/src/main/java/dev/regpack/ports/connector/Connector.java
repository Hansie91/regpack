package dev.regpack.ports.connector;

/**
 * The one connector SPI. Sealed: the permitted kinds are the source kind, the destination kind
 * ({@link Transport}) and (added by a later task) the lookup kind.
 */
public sealed interface Connector permits SourceConnector, Transport {
  ConnectorSpec spec();

  CheckResult check();
}
