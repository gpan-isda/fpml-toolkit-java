// Copyright (C),2005-2024 HandCoded Software Ltd.
// All rights reserved.
//
// This software is licensed in accordance with the terms of the 'Open Source
// License (OSL) Version 3.0'. Please see 'license.txt' for the details.

package com.handcoded.fpml;

import org.xml.sax.SAXException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * A registry that auto-discovers and caches {@link ConversionDeltaProfile}
 * instances from a directory of profile XML files.
 *
 * <h3>Discovery</h3>
 * <p>On first use (or after an explicit {@link #reload()}) the registry scans
 * the configured profile directory for {@code *.xml} files that are valid
 * {@code conversion-delta} documents.  Files that fail to parse are logged as
 * warnings and skipped.</p>
 *
 * <h3>Lookup</h3>
 * <p>Use {@link #profileFor(String, String, String)} to find the best-matching
 * profile for a given from-version / to-version / view triple.  The lookup
 * priority is:</p>
 * <ol>
 *   <li>Exact match on from, to <em>and</em> view.</li>
 *   <li>Match on from and to with a {@code null} / wildcard view (profile
 *       applies to any view).</li>
 * </ol>
 *
 * <h3>Default location</h3>
 * <p>The default profile directory is {@code files-fpml/conversion-profiles}
 * relative to the JVM working directory.  Override with
 * {@link #setProfileDirectory(Path)} <em>before</em> first use.</p>
 *
 * <h3>Thread safety</h3>
 * <p>The registry is lazily loaded on first access.  All public methods are
 * synchronised.</p>
 *
 * @author Andrew Jacobs / ISDA FpML Team
 * @see    ConversionDeltaProfile
 * @since  TFP 1.x
 */
public final class ConversionDeltaProfileRegistry {

    // =========================================================================
    // Configuration
    // =========================================================================

    /** Default directory (relative to CWD) that contains profile XML files. */
    public static final String DEFAULT_PROFILE_DIR = "files-fpml/conversion-profiles";

    /**
     * Overrides the profile directory path.  Must be called before the first
     * lookup / load if a non-default location is desired.
     *
     * @param dir  Absolute or CWD-relative path to the profile directory.
     */
    public static synchronized void setProfileDirectory(Path dir) {
        profileDir = dir;
        loaded     = false;   // force reload on next access
    }

    /**
     * Forces an immediate reload of all profiles from the configured directory,
     * discarding any previously cached entries.
     */
    public static synchronized void reload() {
        profiles.clear();
        loaded = false;
        ensureLoaded();
    }

    // =========================================================================
    // Lookup
    // =========================================================================

    /**
     * Returns the best-matching {@link ConversionDeltaProfile} for the given
     * version transition and view.
     *
     * @param fromVersion  Source version string, e.g. {@code "5-12"}.
     * @param toVersion    Target version string, e.g. {@code "5-13"}.
     * @param view         FpML view (e.g. {@code "confirmation"}), or
     *                     {@code null} to match any view.
     * @return The best-matching profile, or {@code null} if none found.
     */
    public static synchronized ConversionDeltaProfile profileFor(
            String fromVersion, String toVersion, String view) {
        ensureLoaded();

        ConversionDeltaProfile fallback = null;    // view-wildcard match

        for (ConversionDeltaProfile p : profiles) {
            if (!p.getFromVersion().equals(fromVersion)) continue;
            if (!p.getToVersion().equals(toVersion))     continue;

            String pView = p.getFromView();   // null means any-view

            if (pView == null) {
                // wildcard — remember as fallback but keep looking for exact match
                fallback = p;
            } else if (view != null && pView.equalsIgnoreCase(view)) {
                return p;   // exact match
            }
        }
        return fallback;
    }

    /**
     * Returns an unmodifiable snapshot of all successfully loaded profiles.
     */
    public static synchronized List<ConversionDeltaProfile> allProfiles() {
        ensureLoaded();
        return Collections.unmodifiableList(new ArrayList<>(profiles));
    }

    /**
     * Builds an ordered list of {@link ConversionDeltaProfile} instances forming
     * a consecutive chain from {@code fromVersion} to {@code toVersion} for the
     * given {@code view}.
     *
     * <p>The algorithm traverses the profile map hop-by-hop starting at
     * {@code fromVersion}.  At each step it selects the best-matching profile
     * (view-specific match beats wildcard) whose {@code from} equals the current
     * version.  Traversal stops when the current version equals {@code toVersion}
     * or no further profile is found.</p>
     *
     * <p>If no profile exists for a particular hop the chain will be shorter than
     * expected (conversion still succeeds structurally via
     * {@link com.handcoded.meta.Conversion#conversionFor} — profiles only drive
     * enrichment).</p>
     *
     * @param fromVersion  Source version string, e.g. {@code "5-11"}.
     * @param toVersion    Target version string, e.g. {@code "5-13"}.
     * @param view         FpML view (e.g. {@code "confirmation"}), or
     *                     {@code null} to prefer wildcard profiles.
     * @return Ordered (earliest→latest) list of profiles; may be empty.
     */
    public static synchronized List<ConversionDeltaProfile> buildProfileChain(
            String fromVersion, String toVersion, String view) {
        ensureLoaded();

        List<ConversionDeltaProfile> chain   = new ArrayList<>();
        Set<String>                  visited = new HashSet<>();
        String                       current = fromVersion;

        while (current != null && !current.equals(toVersion) && !visited.contains(current)) {
            visited.add(current);

            // Find best-matching profile that starts at 'current'
            ConversionDeltaProfile exact    = null;  // view-specific match
            ConversionDeltaProfile wildcard = null;  // null-view (any-view) match

            for (ConversionDeltaProfile p : profiles) {
                if (!p.getFromVersion().equals(current)) continue;

                String pView = p.getFromView();
                if (pView == null) {
                    if (wildcard == null) wildcard = p;
                } else if (view != null && pView.equalsIgnoreCase(view)) {
                    exact = p;
                    break;
                }
            }

            ConversionDeltaProfile found = (exact != null) ? exact : wildcard;
            if (found == null) break;  // no profile for this hop

            chain.add(found);
            current = found.getToVersion();
        }

        return chain;
    }

    // =========================================================================
    // Private implementation
    // =========================================================================

    private static final Logger LOG =
            Logger.getLogger(ConversionDeltaProfileRegistry.class.getName());

    private static Path                        profileDir =
            Paths.get(DEFAULT_PROFILE_DIR);
    private static boolean                     loaded  = false;
    private static final List<ConversionDeltaProfile> profiles =
            new ArrayList<>();

    /** Prevent instantiation. */
    private ConversionDeltaProfileRegistry() { }

    private static void ensureLoaded() {
        if (loaded) return;
        profiles.clear();

        // Priority 1: external filesystem directory (set via setProfileDirectory)
        if (Files.isDirectory(profileDir)) {
            loadFromDirectory(profileDir);
        } else {
            LOG.fine("ConversionDeltaProfileRegistry: filesystem dir not found: "
                    + profileDir + " — will try classpath fallback.");
        }

        // Priority 2: classpath fallback using bundled profiles.index
        if (profiles.isEmpty()) {
            loadFromClasspath();
        }

        LOG.info("ConversionDeltaProfileRegistry: loaded " + profiles.size()
                + " profile(s) total.");
        loaded = true;
    }

    /** Scans {@code dir} for {@code *.xml} files and loads each as a profile. */
    private static void loadFromDirectory(Path dir) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.xml")) {
            for (Path file : ds) {
                try {
                    ConversionDeltaProfile p =
                            ConversionDeltaProfile.load(file.toFile());
                    profiles.add(p);
                    LOG.fine("Loaded profile (filesystem): " + file.getFileName()
                            + " [" + p.getFromVersion() + " → " + p.getToVersion()
                            + (p.getFromView() != null ? "/" + p.getFromView() : "")
                            + "]");
                } catch (IOException | SAXException e) {
                    LOG.warning("Skipping invalid profile file: "
                            + file.getFileName() + " — " + e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.warning("ConversionDeltaProfileRegistry: error scanning directory: "
                    + dir + " — " + e.getMessage());
        }
        LOG.info("ConversionDeltaProfileRegistry: loaded " + profiles.size()
                + " profile(s) from filesystem dir: " + dir);
    }

    /**
     * Loads profiles from the classpath using the bundled {@code profiles.index}
     * manifest (generated at build time by the antrun plugin).
     *
     * <p>The index file lives at
     * {@code /files-fpml/conversion-profiles/profiles.index} on the classpath
     * and lists one profile XML filename per line.</p>
     */
    private static void loadFromClasspath() {
        InputStream idxStream = ConversionDeltaProfileRegistry.class.getResourceAsStream(
                "/files-fpml/conversion-profiles/profiles.index");
        if (idxStream == null) {
            LOG.info("ConversionDeltaProfileRegistry: no classpath profiles.index found"
                    + " — no bundled profiles loaded.");
            return;
        }

        int count = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(idxStream, StandardCharsets.UTF_8))) {
            String filename;
            while ((filename = reader.readLine()) != null) {
                filename = filename.trim();
                if (filename.isEmpty() || filename.startsWith("#")) continue;

                String resourcePath =
                        "/files-fpml/conversion-profiles/" + filename;
                try {
                    ConversionDeltaProfile p =
                            ConversionDeltaProfile.loadFromClasspath(resourcePath);
                    profiles.add(p);
                    count++;
                    LOG.fine("Loaded profile (classpath): " + filename
                            + " [" + p.getFromVersion() + " → " + p.getToVersion()
                            + (p.getFromView() != null ? "/" + p.getFromView() : "")
                            + "]");
                } catch (IOException | SAXException e) {
                    LOG.warning("Skipping invalid classpath profile: "
                            + resourcePath + " — " + e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.warning("ConversionDeltaProfileRegistry: error reading profiles.index: "
                    + e.getMessage());
        }
        LOG.info("ConversionDeltaProfileRegistry: loaded " + count
                + " profile(s) from classpath.");
    }
}

