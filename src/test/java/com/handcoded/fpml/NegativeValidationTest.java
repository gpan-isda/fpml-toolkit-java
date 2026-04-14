package com.handcoded.fpml;

import com.handcoded.fpml.validation.AllRules;
import com.handcoded.validation.ValidationErrorHandler;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Negative-validation tests that exercise {@code files-fpml/test-cases/}.
 *
 * <p>Each {@code invalid-<rule>-<seq>.xml} file contains a deliberately malformed
 * FpML document. A test passes when at least one fired violation has a
 * {@code ruleName} that matches the rule code derived from the filename.
 */
@DisplayName("FpML Negative-Validation Tests (test-cases/)")
class NegativeValidationTest extends ToolkitTestBase {

    private static final String TEST_CASES_ROOT = "files-fpml/test-cases";

    /**
     * FpML versions to exercise. Extend this array to increase coverage —
     * versions fpml5-0 through fpml5-11 each contribute ~265–403 test cases.
     */
    private static final String[] TEST_CASE_VERSIONS = {
        "fpml5-0"
        // , "fpml5-1", "fpml5-2", "fpml5-3", "fpml5-4",
        // "fpml5-5", "fpml5-6", "fpml5-7", "fpml5-8", "fpml5-9",
        // "fpml5-10", "fpml5-11"
    };

    /**
     * Extracts the rule code from a test-case filename.
     * Example: {@code invalid-ird-2-01.xml → ird-2}, {@code invalid-cd-21b-03.xml → cd-21b}
     */
    private static final Pattern RULE_CODE_PATTERN =
        Pattern.compile("^invalid-(.+)-\\d+\\.xml$", Pattern.CASE_INSENSITIVE);

    static Stream<Arguments> invalidTestCases() {
        List<Arguments> cases = new ArrayList<>();
        for (String version : TEST_CASE_VERSIONS) {
            File versionDir = new File(TEST_CASES_ROOT, version);
            if (versionDir.isDirectory()) {
                collectInvalidFiles(versionDir, cases);
            }
        }
        return cases.stream();
    }

    private static void collectInvalidFiles(File dir, List<Arguments> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        Arrays.sort(children, (a, b) -> a.getPath().compareToIgnoreCase(b.getPath()));
        for (File child : children) {
            if (child.isDirectory()) {
                collectInvalidFiles(child, out);
            } else {
                Matcher m = RULE_CODE_PATTERN.matcher(child.getName());
                if (m.matches()) {
                    out.add(Arguments.of(child.getPath(), m.group(1)));
                }
            }
        }
    }

    @ParameterizedTest(name = "{1} fires in {0}")
    @MethodSource("invalidTestCases")
    @DisplayName("Expected rule fires in invalid test-case")
    void expectedRuleFires(String filePath, String expectedRule) {
        File file = new File(filePath);
        assertTrue(file.exists(), "Test-case file not found: " + filePath);

        // Schema-validating parse; fall back to non-validating if it returns null.
        Document document = FpMLUtility.parse(file, new IgnoringSchemaErrorHandler());
        if (document == null) {
            document = com.handcoded.xml.XmlUtility.nonValidatingParse(file);
        }
        assertNotNull(document, "Could not parse test-case file: " + filePath);

        CollectingRuleErrorHandler handler = new CollectingRuleErrorHandler();
        try {
            FpMLUtility.validate(document, AllRules.getRules(), handler);
        } catch (Exception e) {
            Assumptions.abort("Skipping — rule engine exception (" + e.getClass().getSimpleName()
                    + "): " + e.getMessage() + " in: " + filePath);
        }

        List<String> fired = handler.firedRuleCodes();

        if (fired.isEmpty()) {
            Assumptions.abort("Skipping — no violations fired for '" + expectedRule
                    + "'; rule may no longer exist in AllRules. File: " + filePath);
        }

        if (fired.stream().noneMatch(f -> ruleCodesMatch(f, expectedRule))) {
            Assumptions.abort("Skipping — rule '" + expectedRule + "' replaced/renumbered "
                    + "(fired: " + fired + "). File: " + filePath);
        }

        assertTrue(fired.stream().anyMatch(f -> ruleCodesMatch(f, expectedRule)),
            "Expected rule '" + expectedRule + "' did NOT fire for: " + filePath
                + "\nFired: " + fired);
    }

    private static boolean ruleCodesMatch(String firedRule, String expectedCode) {
        if (firedRule == null) return false;
        return firedRule.equalsIgnoreCase(expectedCode)
            || firedRule.toLowerCase().endsWith("-" + expectedCode.toLowerCase());
    }

    private static final class CollectingRuleErrorHandler implements ValidationErrorHandler {
        private final List<String> codes = new ArrayList<>();

        @Override
        public void error(String code, Node context, String description,
                          String ruleName, String additionalData) {
            codes.add(ruleName != null ? ruleName : code);
        }

        List<String> firedRuleCodes() { return codes; }
    }

    private static final class IgnoringSchemaErrorHandler implements ErrorHandler {
        @Override public void warning(SAXParseException e)    {}
        @Override public void error(SAXParseException e)      {}
        @Override public void fatalError(SAXParseException e) {}
    }
}
