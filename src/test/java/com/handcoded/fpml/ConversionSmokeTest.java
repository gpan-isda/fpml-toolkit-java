package com.handcoded.fpml;

import com.handcoded.fpml.validation.AllRules;
import com.handcoded.meta.Conversion;
import com.handcoded.meta.ConversionException;
import com.handcoded.meta.Release;
import com.handcoded.meta.Specification;
import com.handcoded.validation.ValidationErrorHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke-tests for FpML document conversion.
 *
 * <p>Two parameterised tests:
 * <ol>
 *   <li>{@link #convert5xIrd01} — converts the FpML 5-0 confirmation vanilla-swap
 *       example to each of the 13 downstream 5.x versions.</li>
 *   <li>{@link #convert4xIrd01} — converts the 4.x vanilla-swap example from each
 *       4.x version up to 5-13 confirmation, then validates the result with AllRules.</li>
 * </ol>
 *
 * <p>Each test asserts:
 * <ul>
 *   <li>The conversion path exists ({@link Conversion#conversionFor} chains hops via
 *       {@code IndirectConversion} — no manual BFS needed).</li>
 *   <li>The converted document's release equals the target release.</li>
 *   <li>{@link AllRules} fires no validation errors on the converted document.</li>
 * </ul>
 *
 * Run with: {@code mvnd test} (or {@code mvnd test -Dtest=ConversionSmokeTest})
 */
@DisplayName("FpML Conversion Smoke Tests")
class ConversionSmokeTest extends ToolkitTestBase {

    /** Confirmation IRD vanilla-swap example present in all 5.x versions. */
    private static final String IRD_5_0 =
        "files-fpml/examples/fpml5-0/confirmation/products/interest-rate-derivatives/ird-ex01-vanilla-swap.xml";

    // -------------------------------------------------------------------------
    // 5.x → 5.x conversion tests
    // -------------------------------------------------------------------------

    /**
     * Converts the FpML 5-0 vanilla-swap confirmation example to each
     * downstream 5.x version and validates the result with AllRules.
     */
    @ParameterizedTest(name = "5-0 vanilla-swap → {0}")
    @CsvSource({
        "5-1",  "5-2",  "5-3",  "5-4",  "5-5",
        "5-6",  "5-7",  "5-8",  "5-9",  "5-10",
        "5-11", "5-12", "5-13"
    })
    @DisplayName("Convert FpML 5-0 IRD example to target 5.x version")
    void convert5xIrd01(String targetVersion) throws ConversionException {

        File file = new File(IRD_5_0);
        assertTrue(file.exists(), "Source example not found: " + IRD_5_0);

        Document document = com.handcoded.xml.XmlUtility.nonValidatingParse(file);
        assertNotNull(document, "Failed to parse source document");

        Release source = Specification.releaseForDocument(document);
        assertNotNull(source, "Could not identify source release for 5-0 example");

        Release target = Releases.compatibleRelease(document, targetVersion);
        assertNotNull(target, "No compatible release found for version " + targetVersion);

        // Conversion.conversionFor() resolves multi-hop paths automatically via IndirectConversion.
        Conversion conversion = Conversion.conversionFor(source, target);
        assertNotNull(conversion,
            "No conversion path found from " + source.getVersion() + " to " + targetVersion);

        Document result = conversion.convert(document, new DefaultHelper());
        assertNotNull(result, "Conversion returned null document");

        Release resultRelease = Specification.releaseForDocument(result);
        assertNotNull(resultRelease, "Could not identify release of converted document");
        assertEquals(target, resultRelease,
            "Converted release " + resultRelease.getVersion() + " != target " + targetVersion);

        assertNoAllRulesViolations(result, "5-0 -> " + targetVersion);
    }

    // -------------------------------------------------------------------------
    // 4.x → 5-13 conversion tests  (cross-generation)
    // -------------------------------------------------------------------------

    /**
     * Converts each bundled 4.x vanilla-swap example all the way to 5-13
     * confirmation, then runs AllRules to verify semantic correctness.
     *
     * <p>The source path is constructed as:
     * {@code files-fpml/examples/fpml4-<n>/interest-rate-derivatives/ird-ex01-vanilla-swap.xml}
     */
    @ParameterizedTest(name = "4-{0} vanilla-swap -> 5-13")
    @CsvSource({
        "2", "3", "4", "5", "6", "7", "8", "9", "10"
    })
    @DisplayName("Convert FpML 4.x IRD example to 5-13 confirmation")
    void convert4xIrd01(String minorVersion) throws ConversionException {
        String srcVersion   = "4-" + minorVersion;
        String srcPath      = "files-fpml/examples/fpml4-" + minorVersion
                                  + "/interest-rate-derivatives/ird-ex01-vanilla-swap.xml";
        String targetVersion = "5-13";

        File file = new File(srcPath);
        // Skip gracefully when a particular 4.x version does not ship the example.
        org.junit.jupiter.api.Assumptions.assumeTrue(file.exists(),
            "Skipping " + srcVersion + " — example not found: " + srcPath);

        Document document = com.handcoded.xml.XmlUtility.nonValidatingParse(file);
        assertNotNull(document, "Failed to parse " + srcPath);

        Release source = Specification.releaseForDocument(document);
        assertNotNull(source, "Could not identify source release for " + srcPath);

        Release target = Releases.compatibleRelease(document, targetVersion);
        assertNotNull(target, "No compatible release found for version " + targetVersion);

        Conversion conversion = Conversion.conversionFor(source, target);
        assertNotNull(conversion,
            "No conversion path found from " + srcVersion + " to " + targetVersion);

        Document result = conversion.convert(document, new DefaultHelper());
        assertNotNull(result, "Conversion returned null document");

        Release resultRelease = Specification.releaseForDocument(result);
        assertNotNull(resultRelease, "Could not identify release of converted document");
        assertEquals(target, resultRelease,
            "Converted release " + resultRelease.getVersion() + " != target " + targetVersion);

        assertNoAllRulesViolations(result, srcVersion + " -> " + targetVersion);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Runs {@link AllRules} against {@code document} and fails the test if any
     * rule fires.
     *
     * <p>Converted DOMs are built via DOM manipulation and lack xsi:type annotations
     * (schema type info).  Rules that rely on type info (e.g. {@code ref-7}) produce
     * false positives against such DOMs.  We therefore serialize the converted document
     * to XML and re-parse it with the schema-validating parser before validation;
     * this injects the required type information.  If the schema re-parse fails we
     * fall back to the original DOM (rules that need type info may not work, but at
     * least structural rules are covered).
     *
     * @param document  converted document to validate
     * @param label     human-readable label used in the failure message
     */
    static void assertNoAllRulesViolations(Document document, String label) {
        String xml = serializeToXml(document);

        // Re-parse with schema-validation to inject type annotations.
        SilentSchemaErrorHandler schemaErrors = new SilentSchemaErrorHandler();
        Document annotated = FpMLUtility.parse(xml, schemaErrors);
        Document toValidate = (annotated != null) ? annotated : document;

        CollectingErrorHandler errors = new CollectingErrorHandler();
        FpMLUtility.validate(toValidate, AllRules.getRules(), errors);
        assertTrue(errors.getErrors().isEmpty(),
            "AllRules violations after conversion (" + label + "):\n"
                + String.join("\n", errors.getErrors()));
    }

    /** Serializes a DOM {@link Document} to a UTF-8 XML string. */
    private static String serializeToXml(Document document) {
        try {
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            StringWriter sw = new StringWriter();
            t.transform(new DOMSource(document), new StreamResult(sw));
            return sw.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize converted document", e);
        }
    }

    // -------------------------------------------------------------------------
    // Collecting error handler (shared via package scope)
    // -------------------------------------------------------------------------

    static final class CollectingErrorHandler implements ValidationErrorHandler {
        private final List<String> errors = new ArrayList<>();

        @Override
        public void error(String code, Node context, String description,
                          String ruleName, String additionalData) {
            errors.add(ruleName + ": " + description
                + (additionalData != null ? " [" + additionalData + "]" : ""));
        }

        List<String> getErrors() { return errors; }
    }

    /** SAX {@link ErrorHandler} that silently discards schema-validation problems. */
    static final class SilentSchemaErrorHandler implements ErrorHandler {
        @Override public void warning(SAXParseException e)    { /* ignore */ }
        @Override public void error(SAXParseException e)      { /* ignore */ }
        @Override public void fatalError(SAXParseException e) { /* ignore */ }
    }
}

