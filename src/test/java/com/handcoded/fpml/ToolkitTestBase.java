package com.handcoded.fpml;

import com.handcoded.meta.Specification;
import com.handcoded.xml.XmlUtility;
import com.handcoded.xml.resolver.CatalogManager;
import org.junit.jupiter.api.BeforeAll;
import org.xml.sax.SAXException;

import java.io.File;

/**
 * Base class that bootstraps the FpML toolkit before any test runs.
 *
 * The toolkit loads its metadata from relative paths (files-core/releases.xml,
 * files-fpml/catalog-fpml-*.xml, …).  These paths are resolved relative to
 * the JVM working directory, so the JVM must be started with the project root
 * as its working directory.  Maven Surefire is configured to do that via
 * {@code <workingDirectory>${project.basedir}</workingDirectory>} in pom.xml.
 *
 * This base class calls {@link Specification#specifications()} (which is a
 * no-op once initialised) to force eager initialisation and provide a clear
 * failure message if the bootstrap path is wrong.
 */
abstract class ToolkitTestBase {

    @BeforeAll
    static void bootstrapToolkit() throws Exception {
        // Print the working directory so test failures give useful diagnostics.
        String workDir = System.getProperty("user.dir", "(unknown)");
        System.out.println("[ToolkitTestBase] user.dir = " + workDir);

        // Force eager initialisation of the specification registry.
        Specification[] specs = Specification.specifications();
        if (specs == null || specs.length == 0) {
            throw new IllegalStateException(
                "FpML toolkit bootstrap failed – Specification registry is empty.\n"
                + "Make sure tests run with working directory = project root.\n"
                + "Current user.dir = " + System.getProperty("user.dir"));
        }
        System.out.println("[ToolkitTestBase] Specifications loaded: " + specs.length);

        // Initialise the default XML catalog so that schema-validating parses work.
        // Use catalog-fpml-5-13.xml which maps FpML 5.x namespace URIs as well as
        // all FpML 4.x URIs (via its <nextCatalog> chain to catalog-fpml.xml).
        //
        // NOTE: We always (re-)set the catalog here – the == null guard was removed
        // because in the full test suite a previous test class may have set catalog-fpml.xml
        // (which lacks FpML 5.x URI mappings), leaving the schema compiled with the wrong
        // catalog and causing NullPointerExceptions in subsequent schema-validating parses.
        String catalogPath = "files-fpml/catalog-fpml-5-13.xml";
        if (new File(catalogPath).exists()) {
            try {
                XmlUtility.setDefaultCatalog(CatalogManager.find(catalogPath));
                System.out.println("[ToolkitTestBase] Catalog loaded: " + catalogPath);
            } catch (SAXException e) {
                System.out.println("[ToolkitTestBase] WARNING: Failed to load catalog: " + e.getMessage());
            }
        } else {
            System.out.println("[ToolkitTestBase] WARNING: Catalog file not found: " + catalogPath);
        }

        // Pre-compile the default schema set with the correct catalog now so that
        // it is ready (and non-null) before any test method calls FpMLUtility.parse().
        // Without this, lazy compilation may use a stale or wrong catalog set by an
        // earlier test class in the suite.
        if (XmlUtility.getDefaultCatalog() != null) {
            XmlUtility.getDefaultSchemaSet().getSchema();
            System.out.println("[ToolkitTestBase] Default schema set pre-compiled.");
        }
    }
}
