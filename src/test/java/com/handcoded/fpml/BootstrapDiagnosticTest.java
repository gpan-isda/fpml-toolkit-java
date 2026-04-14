package com.handcoded.fpml;

import com.handcoded.framework.Application;
import com.handcoded.meta.Specification;
import com.handcoded.xml.XmlUtility;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/** Diagnostic test – not a real smoke test, run once to debug bootstrap. */
class BootstrapDiagnosticTest {

    @Test
    void diagnoseBootstrap() throws Exception {
        System.out.println("=== DIAGNOSTIC ===");
        System.out.println("user.dir: " + System.getProperty("user.dir"));

        // Check if key files are accessible
        File releases = new File("files-core/releases.xml");
        System.out.println("files-core/releases.xml exists: " + releases.exists());
        System.out.println("files-core/releases.xml absolute: " + releases.getAbsolutePath());

        File fpmlMeta = new File("files-fpml/meta/fpml-5-0.xml");
        System.out.println("files-fpml/meta/fpml-5-0.xml exists: " + fpmlMeta.exists());

        // Check what DocumentBuilderFactory is being used
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        System.out.println("DocumentBuilderFactory: " + dbf.getClass().getName());
        System.out.println("XInclude supported: " + dbf.isXIncludeAware() + " (after set: " + "configuring...)");

        // Try to open and parse releases.xml directly
        InputSource source = Application.openInputSource("files-core/releases.xml");
        System.out.println("InputSource: " + source);
        if (source != null) {
            System.out.println("InputSource systemId: " + source.getSystemId());
        }

        Document doc = null;
        if (source != null) {
            try {
                doc = XmlUtility.nonValidatingParseWithXInclude(source);
                System.out.println("Parsed document: " + doc);
                if (doc != null) {
                    System.out.println("Root element: " + doc.getDocumentElement().getLocalName());
                    System.out.println("Children count: " + doc.getDocumentElement().getChildNodes().getLength());
                }
            } catch (Exception e) {
                System.out.println("EXCEPTION parsing releases.xml: " + e);
                e.printStackTrace(System.out);
            }
        }

        // Check Specification registry
        Specification[] specs = Specification.specifications();
        System.out.println("Registered specifications: " + specs.length);
        for (Specification s : specs) {
            System.out.println("  - " + s.getName());
        }

        System.out.println("=== END DIAGNOSTIC ===");
        // Don't assert – just print
        assertTrue(true);
    }
}

