/**
 * The report flow of the thin slice: the stages between a source file and the transport, and back
 * (ingest, enrichment, validation, the report, the output file, sending and the feedback).
 *
 * <p>The module is a deterministic library on the model, the ports, the pack and the engine only.
 * It reads the clock, randomness and the locale through the injected ports, names no regime, route
 * or authority as a literal (the destination is profile data), and holds synthetic samples only.
 * Everything here is NON-PRODUCTION.
 */
package dev.regpack.flow;
