package com.handcoded.fpml;

import com.handcoded.fpml.validation.AllRules;
import com.handcoded.validation.ValidationErrorHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke-tests for FpML business-rule (semantic) validation.
 *
 * Each test parses a known-good example file and runs the full AllRules
 * rule-set against it.  The test fails if any validation error is reported.
 *
 * Run with: mvn test  (or  mvn test -Dtest=ValidationSmokeTest)
 */
@DisplayName("FpML Validation Smoke Tests")
class ValidationSmokeTest extends ToolkitTestBase {

    /**
     * A sample confirmation IRD example from each supported FpML 5.x version.
     * Format: "version, relative-path"
     */
    @ParameterizedTest(name = "validate {0} ird-ex01-vanilla-swap")
    @CsvSource({
        "5-0,  files-fpml/examples/fpml5-0/confirmation/products/interest-rate-derivatives/ird-ex01-vanilla-swap.xml",
        "5-1,  files-fpml/examples/fpml5-1/confirmation/products/interest-rate-derivatives/ird-ex01-vanilla-swap.xml",
        "5-2,  files-fpml/examples/fpml5-2/confirmation/products/interest-rate-derivatives/ird-ex01-vanilla-swap.xml",
        "5-3,  files-fpml/examples/fpml5-3/confirmation/products/interest-rate-derivatives/ird-ex01-vanilla-swap.xml",
        "5-4,  files-fpml/examples/fpml5-4/confirmation/products/interest-rate-derivatives/ird-ex01-vanilla-swap.xml",
        "5-5,  files-fpml/examples/fpml5-5/confirmation/products/interest-rate-derivatives/ird-ex01-vanilla-swap.xml",
        "5-6,  files-fpml/examples/fpml5-6/confirmation/products/interest-rate-derivatives/ird-ex01-vanilla-swap.xml",
        "5-7,  files-fpml/examples/fpml5-7/confirmation/products/interest-rate-derivatives/ird-ex01-vanilla-swap.xml",
        "5-8,  files-fpml/examples/fpml5-8/confirmation/products/interest-rate-derivatives/ird-ex01-vanilla-swap.xml",
        "5-9,  files-fpml/examples/fpml5-9/confirmation/products/interest-rate-derivatives/ird-ex01-vanilla-swap.xml",
        "5-10, files-fpml/examples/fpml5-10/confirmation/products/interest-rate-derivatives/ird-ex01-vanilla-swap.xml",
        "5-11, files-fpml/examples/fpml5-11/confirmation/products/interest-rate-derivatives/ird-ex01-vanilla-swap.xml",
        "5-12, files-fpml/examples/fpml5-12/confirmation/products/interest-rate-derivatives/ird-ex01-vanilla-swap.xml",
        "5-13, files-fpml/examples/fpml5-13/confirmation/products/interest-rate-derivatives/ird-ex01-vanilla-swap.xml"
    })
    @DisplayName("Semantic validation passes for bundled ird-ex01 examples")
    void validateIrd01(String version, String path) {
        File file = new File(path.trim());
        assertTrue(file.exists(),
            "Example file not found for version " + version + ": " + path.trim()
            + " — make sure you are running from the project root.");

        // Use the schema-validating parser so that DOM nodes carry type information.
        // Without type info the ReferenceRule falls back to element-name matching,
        // which produces false positives for some rules on FpML 5.x documents.
        CollectingSchemaErrorHandler schemaErrors = new CollectingSchemaErrorHandler();
        Document document = FpMLUtility.parse(file, schemaErrors);
        assertNotNull(document,
            "Failed to parse " + path.trim()
            + (schemaErrors.hasErrors() ? "\nSchema errors:\n" + schemaErrors.summary() : ""));

        CollectingErrorHandler errors = new CollectingErrorHandler();
        FpMLUtility.validate(document, AllRules.getRules(), errors);

        assertTrue(errors.getErrors().isEmpty(),
            "Validation errors in " + version + " example:\n"
                + String.join("\n", errors.getErrors()));
    }

    // -------------------------------------------------------------------------
    // Simple collecting error handler (business-rule errors)
    // -------------------------------------------------------------------------

    private static final class CollectingErrorHandler implements ValidationErrorHandler {
        private final List<String> errors = new ArrayList<>();

        @Override
        public void error(String code, Node context, String description,
                          String ruleName, String additionalData) {
            errors.add(ruleName + ": " + description
                + (additionalData != null ? " [" + additionalData + "]" : ""));
        }

        List<String> getErrors() { return errors; }
    }

    // -------------------------------------------------------------------------
    // SAX ErrorHandler that collects schema-validation errors without throwing
    // -------------------------------------------------------------------------

    private static final class CollectingSchemaErrorHandler implements ErrorHandler {
        private final List<String> messages = new ArrayList<>();

        @Override public void warning(SAXParseException e)  { messages.add("WARN:  " + e.getMessage()); }
        @Override public void error(SAXParseException e)    { messages.add("ERROR: " + e.getMessage()); }
        @Override public void fatalError(SAXParseException e) { messages.add("FATAL: " + e.getMessage()); }

        boolean hasErrors() { return !messages.isEmpty(); }
        String  summary()   { return String.join("\n", messages); }
    }
}

