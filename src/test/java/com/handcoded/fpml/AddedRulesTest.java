package com.handcoded.fpml;

import com.handcoded.fpml.validation.AllRules;
import com.handcoded.validation.ValidationErrorHandler;
import com.handcoded.xml.XmlUtility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hard-asserting tests for the rules added from the FpML 5.12–5.14 specifications
 * (eqd-33..eqd-45, ln-37..ln-46) and for ln-34, which was only registered in
 * {@code business-rules.xml} alongside them.
 *
 * <p>EQD rules use the invalid test cases published with the FpML 5.14 REC.
 * The spec ships none for ln-37..ln-46, so the loan rules use small synthetic
 * documents with a passing and a failing variant each. Documents are parsed
 * without schema validation, as the message-flow-builder backend does, which
 * exercises the rules' name-based lookup. (The schema-validating parse used by
 * {@link NegativeValidationTest} does not resolve the FpML 5 schemas in this
 * test setup, so no rules fire on it and its cases are skipped.)
 */
@DisplayName("Rules added for FpML 5-12 to 5-14")
class AddedRulesTest extends ToolkitTestBase {

    private static final String TEST_CASES = "files-fpml/test-cases/fpml5-14/confirmation/invalid-testcases";

    private static final Pattern RULE_CODE = Pattern.compile("^invalid-(.+)-\\d+\\.xml$");

    // ---------------------------------------------------------------------
    // EQD rules (spec invalid test cases)
    // ---------------------------------------------------------------------

    static Stream<Arguments> eqdTestCases() {
        File[] files = new File(TEST_CASES).listFiles();
        assertNotNull(files, "Missing test-case directory " + TEST_CASES);
        Arrays.sort(files);
        List<Arguments> cases = new ArrayList<>();
        for (File file : files) {
            Matcher m = RULE_CODE.matcher(file.getName());
            if (m.matches()) cases.add(Arguments.of(file.getName(), m.group(1)));
        }
        assertEquals(13, cases.size(), "Expected the 13 invalid test cases for eqd-33..eqd-45");
        return cases.stream();
    }

    @ParameterizedTest(name = "{1} fires on {0}")
    @MethodSource("eqdTestCases")
    void eqdRuleFires(String fileName, String rule) throws Exception {
        Document document = XmlUtility.nonValidatingParse(new File(TEST_CASES, fileName));
        assertNotNull(document, "Could not parse " + fileName);
        assertTrue(fired(document).contains(rule), rule + " did not fire on " + fileName + "; fired: " + fired(document));
    }

    @Test
    @DisplayName("eqd-36 (5-14 and later) does not apply to a 5-13 document")
    void eqd36NotAppliedBefore514() throws Exception {
        String xml = new String(Files.readAllBytes(new File(TEST_CASES, "invalid-eqd-36-01.xml").toPath()), StandardCharsets.UTF_8)
                .replace("fpmlVersion=\"5-14\"", "fpmlVersion=\"5-13\"");
        assertTrue(xml.contains("fpmlVersion=\"5-13\""), "Test case no longer declares fpmlVersion 5-14");
        assertFalse(fired(parse(xml)).contains("eqd-36"));
    }

    // ---------------------------------------------------------------------
    // Loan rules (synthetic documents)
    // ---------------------------------------------------------------------

    @Test
    void ln37FinalApprovalDateNotInFuture() throws Exception {
        assertFires("ln-37", approval(null, LocalDate.now().plusYears(1).toString()));
        assertDoesNotFire("ln-37", approval(null, "2020-01-01"));
    }

    @Test
    void ln38FinalApprovalDateNotAfterEffectiveDate() throws Exception {
        assertFires("ln-38", approval("2024-01-01", "2024-02-01"));
        assertDoesNotFire("ln-38", approval("2024-01-01", "2023-12-01"));
    }

    @Test
    void ln39TaskDatesNotBeforeRaisedDate() throws Exception {
        assertFires("ln-39", task("<expectedDate>2024-01-09</expectedDate>"));
        assertFires("ln-39", task("<clearedDate>2024-01-09</clearedDate>"));
        assertDoesNotFire("ln-39", task("<expectedDate>2024-01-10</expectedDate><clearedDate>2024-01-11</clearedDate>"));
    }

    @Test
    void ln40CovenantNotBeforeCreditAgreement() throws Exception {
        assertFires("ln-40", deal("2024-01-01"));
        assertDoesNotFire("ln-40", deal("2024-02-01"));
    }

    @Test
    void ln41NoCompoundingBalanceWhenAveraging() throws Exception {
        assertFires("ln-41", accrualRate("Averaging", "true"));
        assertDoesNotFire("ln-41", accrualRate("Averaging", "false"));
        assertDoesNotFire("ln-41", accrualRate("Compounding", "true"));
    }

    @Test
    void ln42MarginPlusIndexLimitEqualsAllInLimit() throws Exception {
        assertFires("ln-42", rateLimits("capRateSchedule", "capRate", "0.08", ""));
        assertDoesNotFire("ln-42", rateLimits("capRateSchedule", "capRate", "0.07", ""));
        assertFires("ln-42", rateLimits("floorRateSchedule", "floorRate", "0.08", ""));
        assertDoesNotFire("ln-42", rateLimits("floorRateSchedule", "floorRate", "0.07", ""));
        // A stepped spread has no single value to compare, so it is not checked.
        assertDoesNotFire("ln-42", rateLimits("capRateSchedule", "capRate", "0.08",
                "<step><stepDate>2024-06-01</stepDate><stepValue>0.03</stepValue></step>"));
    }

    @Test
    void ln43AccrualPeriodOrdering() throws Exception {
        assertFires("ln-43", accrualPeriod("2024-02-01", "2024-01-01", "2024-02-01"));
        assertFires("ln-43", accrualPeriod("2024-01-01", "2024-02-01", "2024-01-05"));
        assertDoesNotFire("ln-43", accrualPeriod("2024-01-05", "2024-02-01", "2024-01-01"));
    }

    @Test
    void ln44AllInRateIsSumOfComponents() throws Exception {
        // 0.03 base + 0.025 spread step in force on 2024-04-01 + 0.001 mandatory cost
        assertDoesNotFire("ln-44", allInRate("0.056"));
        assertFires("ln-44", allInRate("0.051"));
    }

    @Test
    void ln45And46PeriodStartNotAfterEnd() throws Exception {
        assertFires("ln-45", optionPeriod("floatingRateOption", "2024-03-01", "2024-02-01"));
        assertDoesNotFire("ln-45", optionPeriod("floatingRateOption", "2024-02-01", "2024-03-01"));
        assertFires("ln-46", optionPeriod("legacyFloatingRateOption", "2024-03-01", "2024-02-01"));
        assertDoesNotFire("ln-46", optionPeriod("legacyFloatingRateOption", "2024-02-01", "2024-03-01"));
    }

    @Test
    @DisplayName("ln-34 only applies when there are allocations")
    void ln34RequiresAllocations() throws Exception {
        String tradeSummary = "<tradeSummary><amount><currency>USD</currency><amount>1000</amount></amount></tradeSummary>";
        assertDoesNotFire("ln-34", doc("loanAllocationNotification", tradeSummary));
        assertFires("ln-34", doc("loanAllocationNotification", tradeSummary
                + "<allocation><amount><currency>USD</currency><amount>400</amount></amount></allocation>"));
        assertDoesNotFire("ln-34", doc("loanAllocationNotification", tradeSummary
                + "<allocation><amount><currency>USD</currency><amount>1000</amount></amount></allocation>"));
    }

    // ---------------------------------------------------------------------
    // Synthetic document builders
    // ---------------------------------------------------------------------

    private static String doc(String root, String body) {
        return "<" + root + " xmlns=\"http://www.fpml.org/FpML-5/confirmation\" fpmlVersion=\"5-14\">" + body + "</" + root + ">";
    }

    private static String approval(String effectiveDate, String finalApprovalDate) {
        return doc("loanLegalActionNotification", "<legalActionApprovalStatus>"
                + (effectiveDate != null ? "<effectiveDate>" + effectiveDate + "</effectiveDate>" : "")
                + "<approvalVotes/><finalApprovalDate>" + finalApprovalDate + "</finalApprovalDate></legalActionApprovalStatus>");
    }

    private static String task(String dates) {
        return doc("loanTradeNotification", "<settlementTask><dates><raisedDate>2024-01-10</raisedDate>" + dates + "</dates></settlementTask>");
    }

    private static String deal(String covenantEffectiveDate) {
        return doc("dealStatement", "<deal><creditAgreementDate>2024-01-10</creditAgreementDate>"
                + "<covenant><effectiveDate>" + covenantEffectiveDate + "</effectiveDate></covenant></deal>");
    }

    private static String accrualRate(String method, String compounding) {
        return doc("loanContractNotice", "<floatingRateAccrual><rate>"
                + "<calculationParameters><calculationMethod>" + method + "</calculationMethod></calculationParameters>"
                + "<isCompoundingBalance>" + compounding + "</isCompoundingBalance></rate></floatingRateAccrual>");
    }

    private static String rateLimits(String schedule, String limit, String limitRate, String spreadSteps) {
        return doc("loanContractNotice", "<floatingRateOption><rate>"
                + "<spreadSchedule><initialValue>0.02</initialValue>" + spreadSteps + "</spreadSchedule>"
                + "<" + schedule + "><initialValue>0.05</initialValue></" + schedule + ">"
                + "<allInRateLimits><" + limit + "><startDate>2024-01-01</startDate><rate>" + limitRate + "</rate></" + limit + "></allInRateLimits>"
                + "</rate></floatingRateOption>");
    }

    private static String accrualPeriod(String start, String end, String rateEffective) {
        return doc("loanContractNotice", "<floatingRateAccrual><startDate>" + start + "</startDate><endDate>" + end + "</endDate>"
                + "<rateEffectiveDate>" + rateEffective + "</rateEffectiveDate></floatingRateAccrual>");
    }

    private static String allInRate(String allIn) {
        return doc("loanContractNotice", "<floatingRateAccrual><rate><spreadSchedule><initialValue>0.02</initialValue>"
                + "<step><stepDate>2024-03-01</stepDate><stepValue>0.025</stepValue></step></spreadSchedule></rate>"
                + "<startDate>2024-04-01</startDate><endDate>2024-05-01</endDate>"
                + "<rateEffectiveDate>2024-04-01</rateEffectiveDate><baseRate>0.03</baseRate>"
                + "<mandatoryCostRate>0.001</mandatoryCostRate><allInRate>" + allIn + "</allInRate></floatingRateAccrual>");
    }

    private static String optionPeriod(String element, String start, String end) {
        return doc("loanContractNotice", "<" + element + "><startDate>" + start + "</startDate><endDate>" + end + "</endDate></" + element + ">");
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static void assertFires(String rule, String xml) throws Exception {
        Set<String> fired = fired(parse(xml));
        assertTrue(fired.contains(rule), rule + " should fire; fired: " + fired + "\n" + xml);
    }

    private static void assertDoesNotFire(String rule, String xml) throws Exception {
        Set<String> fired = fired(parse(xml));
        assertFalse(fired.contains(rule), rule + " should not fire; fired: " + fired + "\n" + xml);
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static Set<String> fired(Document document) {
        Set<String> rules = new TreeSet<>();
        FpMLUtility.validate(document, AllRules.getRules(), new ValidationErrorHandler() {
            @Override
            public void error(String code, Node context, String description, String ruleName, String additionalData) {
                rules.add(ruleName);
            }
        });
        return rules;
    }
}
