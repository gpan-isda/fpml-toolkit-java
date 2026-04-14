// Copyright (C),2005-2024 HandCoded Software Ltd.
// All rights reserved.
//
// This software is licensed in accordance with the terms of the 'Open Source
// License (OSL) Version 3.0'. Please see 'license.txt' for the details.

package com.handcoded.fpml;

import com.handcoded.validation.RuleSet;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Loads additional validation rule definitions from a directory of
 * {@code business-rules.xml}-compatible files and merges them into the global
 * {@link RuleSet} registry.
 *
 * <h3>File format</h3>
 * <p>Each XML file must follow the same SAX-parsed format used by the toolkit's
 * built-in {@code files-core/business-rules.xml}:</p>
 * <pre>{@code
 * <rules>
 *   <ruleSet name="MyRules">
 *     <addRuleSet name="AllRules"/>
 *     <addRule name="my-rule-1"/>
 *   </ruleSet>
 * </rules>
 * }</pre>
 *
 * <h3>Merge behaviour</h3>
 * <p>Rule files are loaded in directory order.  Each {@code <ruleSet>} element
 * either creates a new named {@link RuleSet} (if no set of that name exists yet)
 * or enriches the existing one by adding the declared rules.  Because
 * {@link RuleSet#forName(String)} auto-creates an empty set on first access, a
 * profile that declares {@code <inbound name="AllRules"/>} will always find the
 * (now enriched) set after a {@code loadFrom()} call.</p>
 *
 * <h3>Integration with {@link FpMLConversionPipeline}</h3>
 * <pre>{@code
 * FpMLConversionPipeline pipeline = new FpMLConversionPipeline.Builder("5-13")
 *         .withProfile(profile)
 *         .withValidationRulesPath(Paths.get("/path/to/external/validation"))
 *         .failOnOutboundErrors(true)
 *         .build();
 * }</pre>
 *
 * @author Andrew Jacobs / ISDA FpML Team
 * @see    FpMLConversionPipeline.Builder#withValidationRulesPath(Path)
 * @since  TFP 1.x
 */
public final class ValidationRuleLoader {

    private static final Logger LOG = Logger.getLogger(ValidationRuleLoader.class.getName());

    /**
     * Scans {@code rulesDir} (non-recursively) for {@code *.xml} files, parses each
     * one as a rule-set definition document, and merges all discovered {@link RuleSet}
     * instances into the global registry via {@link RuleSet#forName(String)} and
     * {@link RuleSet#add(RuleSet)}.
     *
     * <p>Files that cannot be parsed are logged as warnings and skipped; they do not
     * cause this method to throw.</p>
     *
     * @param rulesDir  Path to a directory containing rule definition XML files.
     *                  If {@code null} or not a directory, the call is a no-op.
     */
    public static void loadFrom(Path rulesDir) {
        if (rulesDir == null || !Files.isDirectory(rulesDir)) {
            LOG.warning("ValidationRuleLoader.loadFrom: not a directory — " + rulesDir);
            return;
        }

        try (Stream<Path> entries = Files.list(rulesDir)) {
            entries
                .filter(p -> p.getFileName().toString().endsWith(".xml"))
                .sorted()
                .forEach(ValidationRuleLoader::loadFile);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not list rules directory: " + rulesDir, e);
        }
    }

    // -------------------------------------------------------------------------
    // Private implementation
    // -------------------------------------------------------------------------

    private ValidationRuleLoader() { }

    private static void loadFile(Path file) {
        LOG.info("ValidationRuleLoader: loading " + file);
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            SAXParser parser = factory.newSAXParser();
            parser.parse(new InputSource(Files.newInputStream(file)), new BootStrap());
            LOG.info("ValidationRuleLoader: loaded " + file);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Skipping rule file (parse error): " + file, e);
        }
    }

    /**
     * SAX handler that mirrors the format understood by {@code RuleSet}'s internal
     * bootstrap loader:
     * <ul>
     *   <li>{@code <ruleSet name="...">} — creates or enriches a named {@link RuleSet}.</li>
     *   <li>{@code <addRule name="..."/>} — adds a named {@link com.handcoded.validation.Rule}.</li>
     *   <li>{@code <addRuleSet name="..."/>} — merges another {@link RuleSet} into the current one.</li>
     *   <li>{@code <removeRule name="..."/>} — removes a rule by name.</li>
     *   <li>{@code <forceLoad platform="Java" class="..."/>} — forces a class to load.</li>
     * </ul>
     */
    private static final class BootStrap extends DefaultHandler {

        private RuleSet current = null;

        @Override
        public void startElement(String ns, String localName, String qName, Attributes attrs) {
            switch (localName) {
                case "forceLoad": {
                    String platform = attrs.getValue("platform");
                    String impl     = attrs.getValue("class");
                    if ("Java".equals(platform) && impl != null) {
                        try { Class.forName(impl); }
                        catch (ClassNotFoundException e) {
                            LOG.warning("forceLoad: class not found — " + impl);
                        }
                    }
                    break;
                }
                case "ruleSet": {
                    String name = attrs.getValue("name");
                    // forName() creates-or-retrieves; subsequent addRule calls enrich it
                    current = (name != null) ? RuleSet.forName(name) : null;
                    break;
                }
                case "addRule": {
                    if (current == null) break;
                    String name = attrs.getValue("name");
                    com.handcoded.validation.Rule rule =
                            com.handcoded.validation.Rule.forName(name);
                    if (rule != null) {
                        String alias = attrs.getValue("alias");
                        if (alias != null) rule.setAlias(alias);
                        current.add(rule);
                    } else {
                        LOG.warning("addRule: undefined rule '" + name + "'");
                    }
                    break;
                }
                case "addRuleSet": {
                    if (current == null) break;
                    String name   = attrs.getValue("name");
                    RuleSet other = RuleSet.forName(name);
                    if (other != null && other != current)
                        current.add(other);
                    break;
                }
                case "removeRule": {
                    if (current == null) break;
                    String name = attrs.getValue("name");
                    if (name != null) current.remove(name);
                    break;
                }
                default:
                    // other elements (e.g. <rules> root) are ignored
            }
        }

        @Override
        public void endElement(String ns, String localName, String qName) {
            if ("ruleSet".equals(localName)) current = null;
        }
    }
}

