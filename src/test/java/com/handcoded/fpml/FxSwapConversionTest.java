package com.handcoded.fpml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 4.x FX swaps (two {@code fxSingleLeg}) must convert to the 5.x
 * {@code nearLeg} / {@code farLeg} structure. FpML 5.0 has no FX schema, so the
 * 4-10 to 5-0 conversion emits the 5.1+ shape directly.
 */
@DisplayName("4.x FX swap conversion to 5-14")
class FxSwapConversionTest extends ToolkitTestBase {

    private static final String NS = "http://www.fpml.org/FpML-5/confirmation";

    static Stream<String> fxSwapExamples() throws Exception {
        List<String> files;
        try (Stream<Path> s = Files.walk(Paths.get("files-fpml/examples"))) {
            files = s.map(Path::toString)
                     .filter(p -> p.matches(".*[\\\\/]fpml4-\\d+[\\\\/]fx[^\\\\/]*[\\\\/]fx[-_]ex08[-_]fx-?swap\\.xml"))
                     .sorted()
                     .collect(Collectors.toList());
        }
        assertEquals(11, files.size(), "Expected the FX swap example from each of 4-0 .. 4-10: " + files);
        return files.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fxSwapExamples")
    void convertsLegsAndValidates(String path) throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        PipelineResult result = FpMLConversionService.INSTANCE.convert(xml, "5-14", "confirmation");
        Document document = result.getDocument();
        assertNotNull(document, "Conversion failed: " + result.getErrors());

        Element swap = (Element) document.getElementsByTagNameNS(NS, "fxSwap").item(0);
        assertNotNull(swap, "No fxSwap in converted document");
        assertEquals(0, swap.getElementsByTagNameNS(NS, "fxSingleLeg").getLength(), "fxSingleLeg left in fxSwap");
        assertEquals("1.48", rate(swap, "nearLeg"), "near leg should be the first 4.x leg");
        assertEquals("1.5", rate(swap, "farLeg"), "far leg should be the second 4.x leg");

        // 4.x partyId has no scheme; 5.x requires one (the UI prompts for it).
        NodeList partyIds = document.getElementsByTagNameNS(NS, "partyId");
        for (int i = 0; i < partyIds.getLength(); ++i)
            ((Element) partyIds.item(i)).setAttribute("partyIdScheme", "http://www.fpml.org/coding-scheme/external");

        List<String> errors = new ArrayList<>();
        Validator validator = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(new File("files-fpml/schemas/fpml5-14/confirmation/fpml-main-5-14.xsd"))
                .newValidator();
        validator.setErrorHandler(new ErrorHandler() {
            @Override public void warning(SAXParseException e)    {}
            @Override public void error(SAXParseException e)      { errors.add(e.getMessage()); }
            @Override public void fatalError(SAXParseException e) { errors.add(e.getMessage()); }
        });
        // Validate the serialized form, as the backend does: the converted DOM
        // mixes namespace-aware and DOM Level 1 attributes.
        StringWriter serialized = new StringWriter();
        TransformerFactory.newInstance().newTransformer().transform(new DOMSource(document), new StreamResult(serialized));
        validator.validate(new StreamSource(new StringReader(serialized.toString())));
        assertTrue(errors.isEmpty(), "Converted FX swap is not 5-14 schema-valid: " + errors);
    }

    private static String rate(Element swap, String leg) {
        Element el = (Element) swap.getElementsByTagNameNS(NS, leg).item(0);
        assertNotNull(el, "Missing " + leg);
        return el.getElementsByTagNameNS(NS, "rate").item(0).getTextContent().trim();
    }
}
