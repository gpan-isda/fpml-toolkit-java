// Copyright (C),2005-2024 HandCoded Software Ltd.
// All rights reserved.
//
// This software is licensed in accordance with the terms of the 'Open Source
// License (OSL) Version 3.0'. Please see 'license.txt' for the details.

package com.handcoded.fpml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * A {@link RuntimeValueProvider} that prompts the operator on the console
 * (stdin / stdout) for each missing mandatory field encountered during a
 * conversion run.
 *
 * <p>Pressing Enter without typing a value skips the injection for the
 * current document — the field is left absent.  The same prompt will appear
 * again for the next document in a batch if that document also lacks the
 * field.</p>
 *
 * <p>This implementation is <strong>not</strong> thread-safe.  For multi-
 * threaded batch runs supply a locking wrapper or a non-interactive
 * {@link RuntimeValueProvider} implementation instead.</p>
 *
 * @author ISDA FpML Team
 * @since  TFP 1.x
 */
public final class ConsoleRuntimeValueProvider implements RuntimeValueProvider {

    private final BufferedReader in;
    private final PrintStream    out;

    /** Creates a provider that reads from {@code System.in} / writes to {@code System.out}. */
    public ConsoleRuntimeValueProvider() {
        this.in  = new BufferedReader(new InputStreamReader(System.in,  StandardCharsets.UTF_8));
        this.out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    }

    /**
     * Creates a provider that reads/writes to the supplied streams.
     * Useful for testing or embedding in a larger application.
     *
     * @param in   Reader for user input.
     * @param out  Stream for prompts.
     */
    public ConsoleRuntimeValueProvider(BufferedReader in, PrintStream out) {
        this.in  = in;
        this.out = out;
    }

    /**
     * Prompts the user on the console for the value of the missing node.
     *
     * @return The trimmed value typed by the user, or {@code null} if the
     *         user pressed Enter without input (meaning: skip this field).
     */
    @Override
    public String provideValue(String xpath, String constraintType, String note,
                               String locationPath) {
        out.println();
        out.println("  ┌─ RUNTIME PROMPT ──────────────────────────────────────────┐");
        out.printf( "  │ Missing %-52s│%n",
                (constraintType != null ? constraintType : "required") + " field:");
        if (locationPath != null && !locationPath.isEmpty())
            out.printf("  │ Location: %-50s│%n", truncate(locationPath, 50));
        else
            out.printf("  │ XPath : %-52s│%n", truncate(xpath, 52));
        if (note != null && !note.isEmpty())
            out.printf("  │ Note  : %-52s│%n", truncate(note, 52));
        out.println("  └───────────────────────────────────────────────────────────┘");
        out.print(  "  Value [Enter to skip]: ");
        out.flush();

        try {
            String line = in.readLine();
            return (line != null && !line.trim().isEmpty()) ? line.trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026"; // "…"
    }
}

