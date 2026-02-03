package com.handcoded.meta.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Simple CLI tool to generate a HandCoded "schemaRelease" meta file from
 * an FpML schema directory.
 *
 * Usage:
 *   java com.handcoded.meta.tools.MetaGenerator <schemaDir> <version> <outputMetaFile> [mainSchemaFileName] [schemeDefaultMappingFile]
 *
 * Example:
 *   java com.handcoded.meta.tools.MetaGenerator files-fpml/schemas/fpml5-11/confirmation 5-11 files-fpml/meta/fpml-5-11-confirmation.xml
 */
public final class MetaGenerator {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: MetaGenerator <schemaDir> <version> <outputMetaFile> [mainSchemaFileName] [schemeDefaultMappingFile]");
            System.exit(2);
        }

        Path schemaDir = Paths.get(args[0]);
        String version = args[1];
        Path output = Paths.get(args[2]);
        String mainSchemaName = args.length > 3 ? args[3] : null;
        Path schemeMappingFile = args.length > 4 ? Paths.get(args[4]) : null;

        if (!Files.isDirectory(schemaDir)) {
            System.err.println("Schema directory does not exist: " + schemaDir);
            System.exit(3);
        }

        if (mainSchemaName == null) {
            mainSchemaName = findMainSchemaName(schemaDir, version);
            if (mainSchemaName == null) {
                System.err.println("Could not find main schema file (fpml-main-<version>.xsd) in: " + schemaDir);
                System.exit(4);
            }
        }

        Path mainSchema = schemaDir.resolve(mainSchemaName);
        if (!Files.exists(mainSchema)) {
            System.err.println("Main schema file not found: " + mainSchema);
            System.exit(5);
        }

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();

        Document mainDoc = db.parse(mainSchema.toFile());
        Element schemaEl = mainDoc.getDocumentElement();
        String targetNamespace = schemaEl.getAttribute("targetNamespace");

        Set<String> rootElements = collectTopLevelElements(schemaDir, db);

        List<String> schemes = locateSchemeFiles(schemaDir, version);

        List<String> schemeDefaults = determineSchemeDefaults(schemes, schemeMappingFile);

        List<String> imports = collectAndMapImports(schemaDir, db, version);

        Document outDoc = createMetaDocument(version, targetNamespace, mainSchemaName, rootElements, schemes, schemeDefaults, imports, db);

        writeDocument(outDoc, output);

        System.out.println("Wrote meta file: " + output.toAbsolutePath().toString());
    }

    private static String findMainSchemaName(Path schemaDir, String version) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(schemaDir, "fpml-main-*.xsd")) {
            for (Path p : ds) return p.getFileName().toString();
        }
        // fallback: try fpml-main-<version>.xsd
        Path cand = schemaDir.resolve("fpml-main-" + version + ".xsd");
        if (Files.exists(cand)) return cand.getFileName().toString();
        return null;
    }

    private static Set<String> collectTopLevelElements(Path schemaDir, DocumentBuilder db) throws IOException {
        Set<String> roots = new HashSet<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(schemaDir, "*.xsd")) {
            for (Path p : ds) {
                try {
                    Document d = db.parse(p.toFile());
                    Element schemaEl = d.getDocumentElement();
                    NodeList children = schemaEl.getChildNodes();
                    for (int i = 0; i < children.getLength(); ++i) {
                        Node n = children.item(i);
                        if (n.getNodeType() == Node.ELEMENT_NODE) {
                            String local = n.getLocalName();
                            if (local == null) local = n.getNodeName();
                            if ("element".equals(local)) {
                                Element e = (Element) n;
                                if (e.hasAttribute("name")) {
                                    roots.add(e.getAttribute("name"));
                                }
                            }
                        }
                    }
                } catch (SAXException | IOException ex) {
                    System.err.println("Warning: failed to parse XSD: " + p + " : " + ex.getMessage());
                }
            }
        }
        return roots;
    }

    private static List<String> locateSchemeFiles(Path schemaDir, String version) {
        List<String> out = new ArrayList<>();
        // Try to discover the files-fpml/data directory by walking parents
        Path p = schemaDir;
        Path filesFpml = null;
        while (p != null) {
            if (p.getFileName() != null && p.getFileName().toString().equals("files-fpml")) {
                filesFpml = p;
                break;
            }
            p = p.getParent();
        }
        if (filesFpml == null) {
            // try up a few levels
            Path cand = schemaDir;
            for (int i = 0; i < 4 && cand != null; ++i) cand = cand.getParent();
            if (cand != null) {
                Path maybe = cand.resolve("files-fpml");
                if (Files.isDirectory(maybe)) filesFpml = maybe;
            }
        }

        if (filesFpml != null) {
            Path data = filesFpml.resolve("data");
            if (Files.isDirectory(data)) {
                Path schemesFile = data.resolve("schemes" + version + ".xml");
                if (Files.exists(schemesFile)) out.add(relativeUnixPath(schemesFile));
                Path addDefs = data.resolve("additionalDefinitions.xml");
                if (Files.exists(addDefs)) out.add(relativeUnixPath(addDefs));
            }
        }

        // fallback: look in repository relative location
        if (out.isEmpty()) {
            Path fallback = Paths.get("files-fpml", "data", "schemes" + version + ".xml");
            if (Files.exists(fallback)) out.add(relativeUnixPath(fallback));
            Path fallback2 = Paths.get("files-fpml", "data", "additionalDefinitions.xml");
            if (Files.exists(fallback2)) out.add(relativeUnixPath(fallback2));
        }

        return out;
    }

    private static List<String> determineSchemeDefaults(List<String> schemes, Path mappingFile) {
        List<String> defaults = new ArrayList<>();
        if (schemes.isEmpty()) return defaults;

        if (mappingFile != null && Files.exists(mappingFile)) {
            try (BufferedReader br = Files.newBufferedReader(mappingFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    // Use the first mapping line that matches a discovered scheme entry
                    for (String s : schemes) {
                        if (s.endsWith(line) || s.equals(line) || s.contains(line)) {
                            defaults.add(s);
                            break;
                        }
                    }
                    if (!defaults.isEmpty()) break;
                }
            } catch (IOException e) {
                System.err.println("Warning: failed to read scheme mapping file: " + e.getMessage());
            }
        }

        if (defaults.isEmpty()) {
            // deterministic fallback: first scheme in list
            defaults.add(schemes.get(0));
        }

        return defaults;
    }

    private static List<String> collectAndMapImports(Path schemaDir, DocumentBuilder db, String version) {
        List<String> out = new ArrayList<>();
        Set<String> found = new HashSet<>();
        Path filesFpml = findFilesFpmlRoot(schemaDir);

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(schemaDir, "*.xsd")) {
            for (Path p : ds) {
                try {
                    Document d = db.parse(p.toFile());
                    Element schemaEl = d.getDocumentElement();
                    NodeList children = schemaEl.getChildNodes();
                    for (int i = 0; i < children.getLength(); ++i) {
                        Node n = children.item(i);
                        if (n.getNodeType() == Node.ELEMENT_NODE) {
                            String local = n.getLocalName();
                            if (local == null) local = n.getNodeName();
                            if ("import".equals(local)) {
                                Element imp = (Element) n;
                                String schemaLocation = imp.getAttribute("schemaLocation");
                                if (schemaLocation == null || schemaLocation.isEmpty()) continue;

                                String mapped = attemptMapImport(p, schemaLocation, db, filesFpml);
                                if (mapped != null && !found.contains(mapped)) {
                                    out.add(mapped);
                                    found.add(mapped);
                                }
                            }
                        }
                    }
                } catch (SAXException | IOException ex) {
                    System.err.println("Warning: failed to parse XSD for imports: " + p + " : " + ex.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: failed listing XSDs for imports: " + e.getMessage());
        }

        return out;
    }

    private static String attemptMapImport(Path sourceXsd, String schemaLocation, DocumentBuilder db, Path filesFpml) {
        try {
            Path resolved = sourceXsd.getParent().resolve(schemaLocation).normalize();
            String namespace = null;
            if (Files.exists(resolved)) {
                try {
                    Document d = db.parse(resolved.toFile());
                    Element schemaEl = d.getDocumentElement();
                    namespace = schemaEl.getAttribute("targetNamespace");
                } catch (SAXException | IOException ex) {
                    System.err.println("Warning: failed to parse imported XSD: " + resolved + " : " + ex.getMessage());
                }
            }

            // If we discovered a namespace, try to find a meta file containing it
            if (namespace != null && filesFpml != null) {
                Path metaDir = filesFpml.resolve("meta");
                if (Files.isDirectory(metaDir)) {
                    try (DirectoryStream<Path> mds = Files.newDirectoryStream(metaDir, "*.xml")) {
                        for (Path meta : mds) {
                            try {
                                String content = new String(Files.readAllBytes(meta), StandardCharsets.UTF_8);
                                if (content.contains(namespace)) {
                                    return relativeUnixPath(meta);
                                }
                            } catch (IOException e) {
                                // ignore individual file read problems
                            }
                        }
                    } catch (IOException e) {
                        // ignore
                    }
                }
            }

            // Fallback: if resolved exists, return relative path to it; otherwise return original schemaLocation
            if (Files.exists(resolved)) return relativeUnixPath(resolved);
            return schemaLocation;
        } catch (Exception e) {
            return schemaLocation;
        }
    }

    private static Path findFilesFpmlRoot(Path schemaDir) {
        Path p = schemaDir;
        while (p != null) {
            if (p.getFileName() != null && p.getFileName().toString().equals("files-fpml")) {
                return p;
            }
            p = p.getParent();
        }
        // try up a few levels
        Path cand = schemaDir;
        for (int i = 0; i < 4 && cand != null; ++i) cand = cand.getParent();
        if (cand != null) {
            Path maybe = cand.resolve("files-fpml");
            if (Files.isDirectory(maybe)) return maybe;
        }
        return null;
    }

    private static String relativeUnixPath(Path p) {
        // Return path using forward slashes (as seen in repo files)
        Path cwd = Paths.get("").toAbsolutePath();
        try {
            Path rel = cwd.relativize(p.toAbsolutePath());
            return rel.toString().replace(File.separatorChar, '/');
        } catch (Exception e) {
            return p.toString().replace(File.separatorChar, '/');
        }
    }

    private static Document createMetaDocument(String version, String namespaceUri, String schemaLocation,
            Set<String> rootElements, List<String> schemes, List<String> schemeDefaults, List<String> imports, DocumentBuilder db) throws ParserConfigurationException {
        Document out = db.newDocument();
        String releasesNS = "urn:HandCoded:Releases";
        String fpmlNS = "urn:HandCoded:FpML-Releases";
        String xsiNS = "http://www.w3.org/2001/XMLSchema-instance";

        Element root = out.createElementNS(releasesNS, "schemaRelease");
        root.setAttribute("xmlns:fpml", fpmlNS);
        root.setAttribute("xmlns:xsi", xsiNS);
        root.setAttributeNS(xsiNS, "xsi:type", "fpml:SchemaRelease");
        out.appendChild(root);

        // classLoader entries (match existing files)
        Element cl1 = out.createElement("classLoader");
        cl1.setAttribute("platform", "Java");
        cl1.setAttribute("class", "com.handcoded.fpml.meta.FpMLSchemaReleaseLoader");
        root.appendChild(cl1);
        Element cl2 = out.createElement("classLoader");
        cl2.setAttribute("platform", ".Net");
        cl2.setAttribute("class", "HandCoded.FpML.Meta.FpMLSchemaReleaseLoader");
        root.appendChild(cl2);

        Element ver = out.createElement("version"); ver.appendChild(out.createTextNode(version)); root.appendChild(ver);
        Element nsEl = out.createElement("namespaceUri"); nsEl.appendChild(out.createTextNode(namespaceUri)); root.appendChild(nsEl);
        Element sl = out.createElement("schemaLocation"); sl.appendChild(out.createTextNode(schemaLocation)); root.appendChild(sl);
        Element pref = out.createElement("preferredPrefix"); pref.appendChild(out.createTextNode("fpml")); root.appendChild(pref);

        for (String name : rootElements) {
            Element re = out.createElement("rootElement");
            re.appendChild(out.createTextNode(name));
            root.appendChild(re);
        }

        for (String s : schemes) {
            Element fe = out.createElementNS(fpmlNS, "fpml:schemes");
            fe.appendChild(out.createTextNode(s));
            root.appendChild(fe);
        }

        // Add schemeDefault entries
        for (String sd : schemeDefaults) {
            Element sdEl = out.createElement("schemeDefault");
            sdEl.appendChild(out.createTextNode(sd));
            root.appendChild(sdEl);
        }

        // Add import entries
        for (String im : imports) {
            Element impEl = out.createElement("import");
            impEl.appendChild(out.createTextNode(im));
            root.appendChild(impEl);
        }

        return out;
    }

    private static void writeDocument(Document doc, Path out) throws TransformerException, IOException {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

        Files.createDirectories(out.getParent());
        try (FileOutputStream fos = new FileOutputStream(out.toFile())) {
            t.transform(new DOMSource(doc), new StreamResult(fos));
        }
    }

    private MetaGenerator() { /* no instances */ }
}

