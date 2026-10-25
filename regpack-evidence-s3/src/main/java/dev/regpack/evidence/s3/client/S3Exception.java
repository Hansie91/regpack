package dev.regpack.evidence.s3.client;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A call that did not end in its documented success. The message is {@code S3 <operation>
 * status=<n> code=<Code>} and nothing else: no host, key id, secret, bucket, key, request id or
 * text of the server. The code is kept only if it matches {@code [A-Za-z0-9]{1,64}}, else it reads
 * {@code invalid}; {@code none} means the answer named no code and {@code unparsable} that the body
 * could not be read. Status 0 means that there was no HTTP answer at all (a refused connection, a
 * reset, a timeout, an interrupt): the code is then the simple name of the transport exception,
 * which is not chained. The exception has neither a cause nor suppressed exceptions.
 */
public final class S3Exception extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private static final Pattern OPERATION = Pattern.compile("[A-Za-z]{1,32}");
  private static final Pattern CODE = Pattern.compile("[A-Za-z0-9]{1,64}");

  private final String operation;
  private final int status;
  private final String code;

  public S3Exception(String operation, int status, String code) {
    super(message(operation, status, code));
    this.operation = operation;
    this.status = status;
    this.code = CODE.matcher(code).matches() ? code : "invalid";
  }

  private static String message(String operation, int status, String code) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(code, "code");
    if (!OPERATION.matcher(operation).matches()) {
      throw new IllegalArgumentException("operation must be 1 to 32 letters");
    }
    String shown = CODE.matcher(code).matches() ? code : "invalid";
    return "S3 " + operation + " status=" + status + " code=" + shown;
  }

  /** The operation that failed, such as {@code getObject}. */
  public String operation() {
    return operation;
  }

  /** The HTTP status, or 0 when there was no HTTP answer. */
  public int status() {
    return status;
  }

  /** The S3 error code, or one of {@code none}, {@code invalid}, {@code unparsable}. */
  public String code() {
    return code;
  }
}
