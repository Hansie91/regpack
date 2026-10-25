package dev.regpack.evidence.s3;

import dev.regpack.evidence.s3.client.SigV4.Credentials;
import dev.regpack.ports.evidence.EvidenceStore.Durability;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The configuration of a {@link WormEvidenceStore} (D-W9). One lock mode for the whole store: a
 * per-class mode is not built in P3b2. The configuration refuses what it can see to be wrong before
 * any request is made:
 *
 * <ul>
 *   <li>the endpoint is {@code scheme://host[:port]} and nothing else (no user info, path, query or
 *       fragment); {@code http} only for a literal IPv4 address in 127.0.0.0/8, {@code [::1]} or
 *       exactly {@code localhost}, read from the URI without any name lookup; every other host
 *       needs {@code https}; {@link Verification#AWS_S3} needs the host {@code s3.amazonaws.com},
 *       {@code s3.<region>.amazonaws.com} or the same under {@code amazonaws.com.cn};
 *   <li>{@link LockMode#COMPLIANCE} is not combined with {@link Verification#UNVERIFIED_NON_AWS}
 *       (spec 16 Entry: no non-AWS store is called compliance before the SP-O spike);
 *   <li>the declared {@link Durability} is synchronous with at least one failure domain, and
 *       exactly one for {@link Verification#UNVERIFIED_NON_AWS}: the S3 API shows no replication,
 *       so for a store that is not verified the declaration is the operator's, attested at G8, and
 *       a larger count needs an SP-O result ({@link Verification#SP_O_VERIFIED}) or AWS ({@link
 *       Verification#AWS_S3}); a copy counts only if it exists when the PUT is acknowledged, and
 *       asynchronous replication counts zero;
 *   <li>the bucket name follows the S3 naming rules.
 * </ul>
 *
 * Messages never repeat the endpoint, the host, the key id or the secret; {@link #toString} hides
 * them too.
 *
 * @param maxLockDate the latest lock date the adapter asks the store for, whole seconds, at most
 *     the last second of year 9999 (a probe may lower it); a retain-until beyond it is recorded in
 *     the metadata and is not store-enforced
 * @param requestTimeout the deadline of one request, from the call until the last byte of the body
 */
public record WormConfig(
    URI endpoint,
    String region,
    String bucket,
    Credentials credentials,
    LockMode mode,
    Verification verification,
    Durability declaredDurability,
    Instant maxLockDate,
    Duration requestTimeout) {

  /** The lock mode of every object the store writes. */
  public enum LockMode {
    GOVERNANCE,
    COMPLIANCE
  }

  /** What is known about the store's lock semantics and durability. */
  public enum Verification {
    /** An S3-compatible store whose lock semantics SP-O has not verified: graded unverified. */
    UNVERIFIED_NON_AWS,
    /** AWS S3 itself. */
    AWS_S3,
    /** A store for which SP-O has reported. */
    SP_O_VERIFIED
  }

  /** The last second of year 9999, the latest instant a lock date can name. */
  public static final Instant DEFAULT_MAX_LOCK_DATE = Instant.parse("9999-12-31T23:59:59Z");

  /** The default deadline of one request. */
  public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private static final Pattern REGION = Pattern.compile("[A-Za-z0-9._-]{1,64}");
  private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");
  private static final Pattern IPV4_SHAPE = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
  private static final Pattern DECIMAL_OCTET = Pattern.compile("0|[1-9][0-9]{0,2}");
  private static final Pattern REGION_LABEL = Pattern.compile("[a-z0-9-]+");

  public WormConfig {
    Objects.requireNonNull(endpoint, "endpoint");
    Objects.requireNonNull(region, "region");
    Objects.requireNonNull(bucket, "bucket");
    Objects.requireNonNull(credentials, "credentials");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(verification, "verification");
    Objects.requireNonNull(declaredDurability, "declaredDurability");
    Objects.requireNonNull(maxLockDate, "maxLockDate");
    Objects.requireNonNull(requestTimeout, "requestTimeout");
    requireEndpoint(endpoint, verification);
    if (!REGION.matcher(region).matches()) {
      throw new IllegalArgumentException("the region is not usable in a credential scope");
    }
    if (!validBucketName(bucket)) {
      throw new IllegalArgumentException("the bucket name breaks the S3 naming rules");
    }
    if (mode == LockMode.COMPLIANCE && verification == Verification.UNVERIFIED_NON_AWS) {
      throw new IllegalArgumentException(
          "compliance mode needs an AWS or an SP-O verified store (spec 16 Entry)");
    }
    if (!declaredDurability.synchronousAcknowledgement()
        || declaredDurability.independentFailureDomains() < 1) {
      throw new IllegalArgumentException(
          "a store must declare synchronous acknowledgement and at least one failure domain");
    }
    if (verification == Verification.UNVERIFIED_NON_AWS
        && declaredDurability.independentFailureDomains() != 1) {
      throw new IllegalArgumentException(
          "an unverified non-AWS store declares exactly one failure domain; more needs SP-O");
    }
    if (maxLockDate.getNano() != 0
        || !maxLockDate.isAfter(Instant.EPOCH)
        || maxLockDate.isAfter(DEFAULT_MAX_LOCK_DATE)) {
      throw new IllegalArgumentException(
          "maxLockDate must be whole seconds after 1970 and at most 9999-12-31T23:59:59Z");
    }
    if (requestTimeout.isZero() || requestTimeout.isNegative()) {
      throw new IllegalArgumentException("requestTimeout must be positive");
    }
  }

  /**
   * A governance-mode store of a non-AWS service whose lock semantics are not verified (the profile
   * of the CI service), with the default maximum lock date and request timeout.
   *
   * @param declared the operator's declaration: synchronous acknowledgement and exactly one failure
   *     domain
   */
  public static WormConfig governance(
      URI endpoint, String region, String bucket, Credentials credentials, Durability declared) {
    return new WormConfig(
        endpoint,
        region,
        bucket,
        credentials,
        LockMode.GOVERNANCE,
        Verification.UNVERIFIED_NON_AWS,
        declared,
        DEFAULT_MAX_LOCK_DATE,
        DEFAULT_REQUEST_TIMEOUT);
  }

  /**
   * The mode part of the grade: {@code governance} or {@code compliance}, followed by {@code
   * (unverified)} for {@link Verification#UNVERIFIED_NON_AWS}. The profile is always {@code
   * P-WORM}.
   */
  String gradeMode() {
    String base = mode == LockMode.GOVERNANCE ? "governance" : "compliance";
    return verification == Verification.UNVERIFIED_NON_AWS ? base + " (unverified)" : base;
  }

  @Override
  public String toString() {
    return "WormConfig[region="
        + region
        + ", bucket="
        + bucket
        + ", mode="
        + mode
        + ", verification="
        + verification
        + ", declaredDurability="
        + declaredDurability
        + ", maxLockDate="
        + maxLockDate
        + ", requestTimeout="
        + requestTimeout
        + ", endpoint=set, credentials=***]";
  }

  // ---- the endpoint

  private static void requireEndpoint(URI endpoint, Verification verification) {
    String scheme =
        endpoint.getScheme() == null ? "" : endpoint.getScheme().toLowerCase(Locale.ROOT);
    String host = endpoint.getHost();
    if (!(scheme.equals("http") || scheme.equals("https")) || host == null) {
      throw new IllegalArgumentException("the endpoint must be http or https with a host");
    }
    String path = endpoint.getRawPath();
    if (endpoint.getRawUserInfo() != null
        || endpoint.getRawQuery() != null
        || endpoint.getRawFragment() != null
        || !(path == null || path.isEmpty())) {
      throw new IllegalArgumentException("the endpoint must be scheme://host[:port] only");
    }
    int port = endpoint.getPort();
    if (port != -1 && (port < 1 || port > 65535)) {
      throw new IllegalArgumentException("the endpoint port must be 1 to 65535");
    }
    if (scheme.equals("http") && !isLoopbackHost(host)) {
      throw new IllegalArgumentException(
          "plain http is accepted only for a loopback address; use https");
    }
    if (verification == Verification.AWS_S3 && !isAmazonHost(host)) {
      throw new IllegalArgumentException("AWS S3 needs an amazonaws.com endpoint host");
    }
  }

  /** The literal check of the endpoint rule: no name lookup, the text of the host only. */
  private static boolean isLoopbackHost(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    return h.equals("localhost") || h.equals("[::1]") || isLoopbackIpv4(h);
  }

  private static boolean isLoopbackIpv4(String host) {
    String[] octets = host.split("\\.", -1);
    if (octets.length != 4) {
      return false;
    }
    int first = -1;
    for (int i = 0; i < octets.length; i++) {
      if (!DECIMAL_OCTET.matcher(octets[i]).matches()) {
        return false;
      }
      int value = Integer.parseInt(octets[i]);
      if (value > 255) {
        return false;
      }
      if (i == 0) {
        first = value;
      }
    }
    return first == 127;
  }

  /** {@code s3.amazonaws.com}, {@code s3.<region>.amazonaws.com}, and the same under .com.cn. */
  private static boolean isAmazonHost(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    for (String domain : new String[] {"amazonaws.com", "amazonaws.com.cn"}) {
      String head = "s3.";
      String tail = "." + domain;
      if (h.equals(head + domain)) {
        return true;
      }
      if (h.startsWith(head)
          && h.endsWith(tail)
          && h.length() > head.length() + tail.length()
          && REGION_LABEL
              .matcher(h.substring(head.length(), h.length() - tail.length()))
              .matches()) {
        return true;
      }
    }
    return false;
  }

  // ---- the bucket

  private static boolean validBucketName(String name) {
    return BUCKET.matcher(name).matches()
        && !name.contains("..")
        && !IPV4_SHAPE.matcher(name).matches()
        && !name.startsWith("xn--")
        && !name.startsWith("sthree-")
        && !name.endsWith("-s3alias")
        && !name.endsWith("--ol-s3");
  }
}
