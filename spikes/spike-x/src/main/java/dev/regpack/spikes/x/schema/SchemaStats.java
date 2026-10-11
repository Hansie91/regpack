package dev.regpack.spikes.x.schema;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Shape statistics of a synthetic schema, taken by a plain JDK DOM walk. The shape is a declared
 * assumption (spike SP-X, review focus RF9): the schema cannot be compared with a real one while
 * gate G3 is closed, so the statistics are the evidence that the stand-in has the declared weight.
 *
 * <p>Counted <em>under the record</em>: leaf elements, depth, optional share, choices and unbounded
 * groups. The element named {@value #RECORD_ELEMENT} is the root of that walk and is not counted
 * itself; named complex types with element content are expanded at every use. Counted over the
 * whole schema: named types by kind, attributes, the cost drivers.
 */
public final class SchemaStats {
  /** The element whose subtree is measured. */
  public static final String RECORD_ELEMENT = "transaction_record";

  private static final Pattern QUANTIFIER = Pattern.compile("\\{(\\d+)(?:,(\\d*))?\\}");
  private static final BigDecimal OPTIONAL_LOW = new BigDecimal("0.35");
  private static final BigDecimal OPTIONAL_HIGH = new BigDecimal("0.55");

  private SchemaStats() {}

  /**
   * The statistics. {@code optionalShare} is the share (scale 3) of the element declarations under
   * the record, leaves and containers, whose {@code minOccurs} is 0. {@code
   * enumerationsOf180OrMore} counts enumeration types with at least 180 values. {@code
   * patternAlternations} is the largest number of {@code |} characters in one pattern. {@code
   * maxRepetitionBound} is the largest upper bound of a {@code {n}} or {@code {n,m}} quantifier.
   */
  public record Stats(
      int leafElementsUnderTx,
      int maxDepth,
      int namedComplexTypes,
      int enumerationTypes,
      int patternTypes,
      int digitTypes,
      int lengthTypes,
      int choices,
      int unboundedGroups,
      BigDecimal optionalShare,
      int attributes,
      int maxEnumerationSize,
      int patternAlternations,
      int minEnumerationSize,
      int enumerationsOf180OrMore,
      int maxRepetitionBound) {

    /**
     * The statistics that lie outside the declared targets of the task (criterion X-6), by name;
     * empty when the shape is as declared.
     */
    public List<String> outsideTargets() {
      List<String> out = new ArrayList<>();
      if (leafElementsUnderTx != 65) {
        out.add("leafElementsUnderTx");
      }
      if (maxDepth != 5) {
        out.add("maxDepth");
      }
      if (namedComplexTypes < 8) {
        out.add("namedComplexTypes");
      }
      if (enumerationTypes < 12) {
        out.add("enumerationTypes");
      }
      if (patternTypes < 8) {
        out.add("patternTypes");
      }
      if (digitTypes != 6) {
        out.add("digitTypes");
      }
      if (lengthTypes != 6) {
        out.add("lengthTypes");
      }
      if (choices != 3) {
        out.add("choices");
      }
      if (unboundedGroups != 2) {
        out.add("unboundedGroups");
      }
      if (optionalShare.compareTo(OPTIONAL_LOW) < 0 || optionalShare.compareTo(OPTIONAL_HIGH) > 0) {
        out.add("optionalShare");
      }
      if (attributes != 6) {
        out.add("attributes");
      }
      if (minEnumerationSize < 4) {
        out.add("minEnumerationSize");
      }
      if (maxEnumerationSize < 250) {
        out.add("maxEnumerationSize");
      }
      if (enumerationsOf180OrMore < 2) {
        out.add("enumerationsOf180OrMore");
      }
      if (patternAlternations < 3) {
        out.add("patternAlternations");
      }
      if (maxRepetitionBound < 20) {
        out.add("maxRepetitionBound");
      }
      return List.copyOf(out);
    }
  }

  /** The statistics of a schema file. */
  public static Stats of(Path xsd) throws IOException {
    return compute(parse(Files.newInputStream(xsd)));
  }

  /** The statistics of a schema on the class path (a name such as {@code synthetic/txn-1.xsd}). */
  public static Stats ofResource(String resource) throws IOException {
    return compute(parse(open(resource)));
  }

  /**
   * The leaf elements under the record in schema order, as paths relative to the record element
   * ({@code block/inner/leaf}); every alternative of a choice is listed.
   */
  public static List<String> leafPaths(Path xsd) throws IOException {
    return new Walk(parse(Files.newInputStream(xsd))).paths();
  }

  /** The same for a schema on the class path. */
  public static List<String> leafPathsOfResource(String resource) throws IOException {
    return new Walk(parse(open(resource))).paths();
  }

  /** The values of every named enumeration type of a schema on the class path, in schema order. */
  public static Map<String, List<String>> enumerationsOfResource(String resource)
      throws IOException {
    return enumerations(parse(open(resource)));
  }

  /** Every name that the schema declares: elements, attributes, simple and complex types. */
  public static List<String> declaredNames(Path xsd) throws IOException {
    Document doc = parse(Files.newInputStream(xsd));
    List<String> names = new ArrayList<>();
    for (Element e : descendants(doc.getDocumentElement())) {
      String local = e.getLocalName();
      if (e.hasAttribute("name")
          && (local.equals("element")
              || local.equals("attribute")
              || local.equals("simpleType")
              || local.equals("complexType")
              || local.equals("group")
              || local.equals("attributeGroup"))) {
        names.add(e.getAttribute("name"));
      }
    }
    return List.copyOf(names);
  }

  private static InputStream open(String resource) throws IOException {
    InputStream in = SchemaStats.class.getClassLoader().getResourceAsStream(resource);
    if (in == null) {
      throw new IOException("resource not found: " + resource);
    }
    return in;
  }

  private static Document parse(InputStream in) throws IOException {
    try (InputStream closeMe = in) {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setExpandEntityReferences(false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      return builder.parse(closeMe);
    } catch (ParserConfigurationException | SAXException e) {
      throw new IOException("cannot parse the schema: " + e.getMessage(), e);
    }
  }

  private static Stats compute(Document doc) {
    Element schema = doc.getDocumentElement();
    int namedComplex = 0;
    int enumTypes = 0;
    int patternTypes = 0;
    int digitTypes = 0;
    int lengthTypes = 0;
    int maxEnum = 0;
    int minEnum = Integer.MAX_VALUE;
    int bigEnums = 0;
    int alternations = 0;
    int repetition = 0;
    for (Element child : children(schema)) {
      if (child.getLocalName().equals("complexType")) {
        namedComplex++;
      } else if (child.getLocalName().equals("simpleType")) {
        int values = 0;
        boolean pattern = false;
        boolean digits = false;
        boolean length = false;
        for (Element facet : descendants(child)) {
          switch (facet.getLocalName()) {
            case "enumeration" -> values++;
            case "pattern" -> {
              pattern = true;
              String regex = facet.getAttribute("value");
              alternations = Math.max(alternations, count(regex, '|'));
              repetition = Math.max(repetition, maxQuantifier(regex));
            }
            case "totalDigits", "fractionDigits" -> digits = true;
            case "length", "minLength", "maxLength" -> length = true;
            default -> {
              // not a facet that is counted
            }
          }
        }
        if (values > 0) {
          enumTypes++;
          maxEnum = Math.max(maxEnum, values);
          minEnum = Math.min(minEnum, values);
          if (values >= 180) {
            bigEnums++;
          }
        }
        patternTypes += pattern ? 1 : 0;
        digitTypes += digits ? 1 : 0;
        lengthTypes += length ? 1 : 0;
      }
    }
    int attributes = 0;
    for (Element e : descendants(schema)) {
      if (e.getLocalName().equals("attribute") && e.hasAttribute("name")) {
        attributes++;
      }
    }
    Walk walk = new Walk(doc);
    BigDecimal share =
        walk.declarations == 0
            ? BigDecimal.ZERO
            : BigDecimal.valueOf(walk.optional)
                .divide(BigDecimal.valueOf(walk.declarations), 3, RoundingMode.HALF_UP);
    return new Stats(
        walk.leaves,
        walk.maxDepth,
        namedComplex,
        enumTypes,
        patternTypes,
        digitTypes,
        lengthTypes,
        walk.choices,
        walk.unbounded,
        share,
        attributes,
        maxEnum,
        alternations,
        minEnum == Integer.MAX_VALUE ? 0 : minEnum,
        bigEnums,
        repetition);
  }

  private static Map<String, List<String>> enumerations(Document doc) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (Element child : children(doc.getDocumentElement())) {
      if (child.getLocalName().equals("simpleType") && child.hasAttribute("name")) {
        List<String> values = new ArrayList<>();
        for (Element facet : descendants(child)) {
          if (facet.getLocalName().equals("enumeration")) {
            values.add(facet.getAttribute("value"));
          }
        }
        if (!values.isEmpty()) {
          out.put(child.getAttribute("name"), List.copyOf(values));
        }
      }
    }
    return Map.copyOf(out);
  }

  private static int count(String text, char c) {
    int n = 0;
    for (int i = 0; i < text.length(); i++) {
      n += text.charAt(i) == c ? 1 : 0;
    }
    return n;
  }

  private static int maxQuantifier(String regex) {
    int max = 0;
    Matcher matcher = QUANTIFIER.matcher(regex);
    while (matcher.find()) {
      String upper = matcher.group(2);
      String bound = upper == null || upper.isEmpty() ? matcher.group(1) : upper;
      max = Math.max(max, Integer.parseInt(bound));
    }
    return max;
  }

  private static List<Element> children(Element parent) {
    List<Element> out = new ArrayList<>();
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e) {
        out.add(e);
      }
    }
    return out;
  }

  private static List<Element> descendants(Element root) {
    List<Element> out = new ArrayList<>();
    for (Element child : children(root)) {
      out.add(child);
      out.addAll(descendants(child));
    }
    return out;
  }

  /** The walk below the record element. */
  private static final class Walk {
    private final Map<String, Element> complexTypes = new HashMap<>();
    private final List<String> paths = new ArrayList<>();
    int leaves;
    int maxDepth;
    int declarations;
    int optional;
    int choices;
    int unbounded;

    Walk(Document doc) {
      Element schema = doc.getDocumentElement();
      for (Element child : children(schema)) {
        if (child.getLocalName().equals("complexType") && child.hasAttribute("name")) {
          complexTypes.put(child.getAttribute("name"), child);
        }
      }
      Element found = null;
      for (Element e : descendants(schema)) {
        if (e.getLocalName().equals("element") && RECORD_ELEMENT.equals(e.getAttribute("name"))) {
          found = e;
          break;
        }
      }
      Element type = typeOf(Objects.requireNonNull(found, "no element named " + RECORD_ELEMENT));
      if (type == null) {
        throw new IllegalStateException(RECORD_ELEMENT + " has no element content");
      }
      content(type, 1, "");
    }

    List<String> paths() {
      return List.copyOf(paths);
    }

    /** The complex type with element content that an element declaration uses; null for a leaf. */
    private Element typeOf(Element element) {
      Element type = null;
      if (element.hasAttribute("type")) {
        String ref = local(element.getAttribute("type"));
        type = complexTypes.get(ref);
      } else {
        for (Element child : children(element)) {
          if (child.getLocalName().equals("complexType")) {
            type = child;
          }
        }
      }
      if (type == null) {
        return null;
      }
      for (Element child : children(type)) {
        if (child.getLocalName().equals("simpleContent")) {
          return null;
        }
      }
      return type;
    }

    private void content(Element type, int depth, String prefix) {
      for (Element child : children(type)) {
        particle(child, depth, prefix);
      }
    }

    private void particle(Element node, int depth, String prefix) {
      switch (node.getLocalName()) {
        case "sequence", "choice", "all" -> {
          if (node.getLocalName().equals("choice")) {
            choices++;
          }
          if ("unbounded".equals(node.getAttribute("maxOccurs"))) {
            unbounded++;
          }
          for (Element child : children(node)) {
            particle(child, depth, prefix);
          }
        }
        case "element" -> element(node, depth + 1, prefix);
        default -> {
          // attributes and annotations are not particles
        }
      }
    }

    private void element(Element node, int depth, String prefix) {
      declarations++;
      if ("0".equals(node.getAttribute("minOccurs"))) {
        optional++;
      }
      if ("unbounded".equals(node.getAttribute("maxOccurs"))) {
        unbounded++;
      }
      maxDepth = Math.max(maxDepth, depth);
      String path = prefix + node.getAttribute("name");
      Element type = typeOf(node);
      if (type == null) {
        leaves++;
        paths.add(path);
      } else {
        content(type, depth, path + "/");
      }
    }

    private static String local(String qualified) {
      return qualified.substring(qualified.indexOf(':') + 1);
    }
  }
}
