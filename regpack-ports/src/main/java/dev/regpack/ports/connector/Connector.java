package dev.regpack.ports.connector;

/**
 * The one connector SPI. Sealed: the permitted kinds are the source kind, and (added by later
 * tasks) the destination kind and the lookup kind.
 */
public sealed interface Connector permits SourceConnector {
  ConnectorSpec spec();

  CheckResult check();
}
