package demo.com.handcoded.fpml;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collection;

import org.w3c.dom.Document;

import com.handcoded.fpml.Conversions;
import com.handcoded.fpml.DefaultHelper;
import com.handcoded.fpml.Releases;
import com.handcoded.meta.Conversion;
import com.handcoded.meta.ConversionException;
import com.handcoded.meta.Release;
import com.handcoded.meta.Specification;
import com.handcoded.xml.XmlUtility;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;


/**
 * Small automated test that converts a bundled example and asserts the
 * converted document's release equals the requested target. Exits with code
 * 0 on success and non-zero on failure so it can be used in batch scripts.
 */
public final class TestConvertUnit
{
    public static void main (String[] args)
    {
        // Allow overriding the example and target via command-line args.
        // Usage: TestConvertUnit <example-file> <target-version>
        String example;
        String targetVersion;

        if (args != null && args.length >= 2) {
            example = args[0];
            targetVersion = args[1];
        }
        else if (args != null && args.length == 1) {
            example = args[0];
            targetVersion = "5-1"; // default target
        }
        else {
            // Default bundled example and target
            example = "files-fpml/examples/fpml5-0/confirmation/products/interest-rate-derivatives/ird-ex01-vanilla-swap.xml";
            targetVersion = "5-1"; // target to convert to
        }

        // Ensure R5 incremental conversions are registered (defensive)
        ensureR5ConversionRegistration();

        System.out.println ("Example: " + example);
        System.out.println ("Target version: " + targetVersion);

        // DIAGNOSTIC: print all known releases and their outgoing conversions
        System.out.println ("\n--- Registered releases and conversions ---");
        for (Release r : Releases.FPML.releases ()) {
            System.out.println ("Release: " + r + " (version=" + r.getVersion () + ")");
            for (Conversion c : getSourceConversions (r)) {
                System.out.println ("  conversion: " + c.toString () + " -> " + c.getTargetRelease ());
            }
        }
        System.out.println ("--- end list ---\n");

        File file = new File (example);
        if (!file.exists ()) {
            System.err.println ("Example file not found: " + example);
            System.exit (2);
        }

        Document document = XmlUtility.nonValidatingParse (file);
        if (document == null) {
            System.err.println ("Failed to parse example document: " + example);
            System.exit (3);
        }

        Release source = Specification.releaseForDocument (document);
        System.out.println ("Source: " + source);
        if (source == null) {
            System.err.println ("Could not determine source release for example");
            System.exit (4);
        }

        Release target = Releases.compatibleRelease (document, targetVersion);
        if (target == null) {
            System.err.println ("No compatible target release for version: " + targetVersion);
            System.exit (5);
        }

        Conversion conversion = Conversion.conversionFor (source, target);
        if (conversion == null) {
            System.err.println ("No conversion path from " + source + " to " + target);
            // Diagnostic: list outgoing conversions from the source
            System.err.println ("Outgoing conversions from source release:");
            for (Conversion c : getSourceConversions (source)) {
                System.err.println ("  " + c.toString () + " (" + c.getTargetRelease () + ")");
            }

            // Attempt BFS to find any path, printing steps
            System.err.println ("Attempting BFS search for a conversion path...");
            List<Conversion> path = findConversionPath (source, target);

            if (path == null) {
                System.err.println ("No path found by BFS. Cannot convert.");

                // Fallback: try stepwise conversions by instantiating conversion classes directly
                System.err.println ("Attempting fallback stepwise conversion by instantiating Conversions classes...");
                Document chained = attemptStepwiseConversion(document, source, targetVersion);
                if (chained != null) {
                    Release finalRelease = Specification.releaseForDocument (chained);
                    if (finalRelease != null && finalRelease.equals (target)) {
                        System.out.println ("TestConvertUnit: success (fallback chained) - converted document matches target: " + target);
                        System.exit (0);
                    }
                    else {
                        System.err.println ("TestConvertUnit: failure - fallback chained conversion did not reach target");
                        if (finalRelease != null)
                            System.err.println ("Detected release after fallback: " + finalRelease.getVersion ());
                        System.exit (7);
                    }
                }

                System.exit (6);
            }

            System.err.println ("Found conversion path (will apply sequentially):");
            for (Conversion pc : path) {
                System.err.println ("  " + pc.toString () + " (" + pc.getSourceRelease () + " -> " + pc.getTargetRelease () + ")");
            }

            // Apply the conversions in sequence
            Document current = document;
            try {
                for (Conversion pc : path) {
                    current = pc.convert (current, new DefaultHelper ());
                }
            }
            catch (ConversionException e) {
                System.err.println ("Conversion failed during chained conversion: " + e.getMessage ());
                e.printStackTrace (System.err);
                System.exit (8);
            }

            Release result = Specification.releaseForDocument (current);
            if (result != null && result.equals (target)) {
                System.out.println ("TestConvertUnit: success (chained) - converted document matches target: " + target);
                System.exit (0);
            }
            else {
                System.err.println ("TestConvertUnit: failure - chained conversion did not reach target");
                if (result != null)
                    System.err.println ("Detected release after chained conversion: " + result.getVersion ());
                System.exit (7);
            }
        }

        try {
            // Diagnostic: print source root node info
            System.out.println("Source document root: localName=" + document.getDocumentElement().getLocalName() +
                    " namespace=" + document.getDocumentElement().getNamespaceURI());

            Document newDoc = null;
            try {
                //printXml(document);
                newDoc = conversion.convert (document, new DefaultHelper ());
                printXml(newDoc);
            }
            catch (RuntimeException re) {
                System.err.println ("Runtime failure during conversion: " + re.getMessage ());
                re.printStackTrace (System.err);
                System.exit (8);
            }

            Release result = Specification.releaseForDocument (newDoc);
            if (result != null && result.equals (target)) {
                System.out.println ("TestConvertUnit: success - converted document matches target: " + target);
                System.exit (0);
            }
            else {
                System.err.println ("TestConvertUnit: failure - converted document does not match target");
                if (result != null)
                    System.err.println ("Detected release: " + result.getVersion ());
                System.exit (7);
            }
        }
        catch (ConversionException e) {
            System.err.println ("Conversion failed: " + e.getMessage ());
            e.printStackTrace (System.err);
            System.exit (8);
        }
    }

    // Helper to access protected getSourceConversions() via reflection
    @SuppressWarnings("unchecked")
    private static Collection<Conversion> getSourceConversions(Release r) {
        try {
            java.lang.reflect.Method m = Release.class.getDeclaredMethod("getSourceConversions");
            m.setAccessible(true);
            return (Collection<Conversion>) m.invoke(r);
        }
        catch (RuntimeException re) {
            throw re;
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to access getSourceConversions via reflection", e);
        }
    }

    private static List<Conversion> findConversionPath (Release source, Release target)
    {
        Deque<Release> queue = new ArrayDeque<> ();
        Map<Release, Conversion> prevConv = new HashMap<> ();
        Set<Release> visited = new HashSet<> ();

        queue.add (source);
        visited.add (source);

        boolean found = false;
        while (!queue.isEmpty ()) {
            Release r = queue.remove ();
            for (Conversion c : getSourceConversions (r)) {
                Release t = c.getTargetRelease ();
                if (visited.contains (t)) continue;
                visited.add (t);
                prevConv.put (t, c);
                if (t.equals (target)) {
                    found = true;
                    break;
                }
                queue.add (t);
            }
            if (found) break;
        }

        if (!found) {
            System.err.println ("No path found by BFS. Visited releases:");
            for (Release r : visited) System.err.println ("  " + r);
            return (null);
        }

        ArrayList<Conversion> path = new ArrayList<> ();
        Release cur = target;
        while (!cur.equals (source)) {
            Conversion pc = prevConv.get (cur);
            path.add (0, pc);
            cur = pc.getSourceRelease ();
        }
        return (path);
    }

    // Fallback: try stepwise conversions using known version sequence and instantiate Conversions inner classes when necessary
    private static Document attemptStepwiseConversion(Document document, Release source, String targetVersion) {
        String[] sequence = new String[] {"4-8","4-9","4-10","5-0","5-1","5-2","5-3","5-4","5-5","5-6","5-7","5-8","5-9","5-10","5-11","5-12", "5-13"};
        // find starting index > source.getVersion()
        List<String> steps = new ArrayList<>();
        boolean started = false;
        for (String v : sequence) {
            if (!started) {
                if (v.equals(source.getVersion())) started = true; // start from same? we need next
                if (started) continue;
                if (compareVersion(v, source.getVersion()) > 0) started = true;
            }
            if (started) steps.add(v);
            if (v.equals(targetVersion)) break;
        }
        // If steps empty or last != target, construct steps from source up to target inclusive
        if (steps.isEmpty() || !steps.get(steps.size()-1).equals(targetVersion)) {
            // fallback: build from sequence entries greater than source up to target
            steps.clear();
            for (String v : sequence) {
                if (compareVersion(v, source.getVersion()) > 0) {
                    steps.add(v);
                    if (v.equals(targetVersion)) break;
                }
            }
        }

        Document current = document;
        Release currentRelease = source;
        try {
            for (String nextVer : steps) {
                Release nextRelease = findReleaseForVersion(nextVer, current);
                if (nextRelease == null) {
                    System.err.println("No Release object for version " + nextVer);
                    return null;
                }
                Conversion conv = Conversion.conversionFor(currentRelease, nextRelease);
                if (conv == null) {
                    // Try to find and instantiate a Conversions inner class matching the pair
                    conv = findAndInstantiateConversion(currentRelease.getVersion(), nextVer);
                    if (conv == null) {
                        System.err.println("No conversion available from " + currentRelease.getVersion() + " to " + nextVer);
                        return null;
                    }
                }
                System.out.println("Applying conversion: " + conv.toString());
                current = conv.convert(current, new DefaultHelper());
                currentRelease = Specification.releaseForDocument(current);
                if (currentRelease == null) {
                    System.err.println("Could not determine release after conversion to " + nextVer);
                    return null;
                }
            }
        }
        catch (ConversionException e) {
            System.err.println("ConversionException during stepwise conversion: " + e.getMessage());
            e.printStackTrace(System.err);
            return null;
        }
        return current;
    }

    private static int compareVersion(String a, String b) {
        try {
            String[] ap = a.split("-");
            String[] bp = b.split("-");
            int ai = Integer.parseInt(ap[0]);
            int bi = Integer.parseInt(bp[0]);
            if (ai != bi) return ai - bi;
            int aj = Integer.parseInt(ap[1]);
            int bj = Integer.parseInt(bp[1]);
            return aj - bj;
        } catch (Exception ex) { return a.compareTo(b); }
    }

    private static Release findReleaseForVersion(String version, Document document) {
        // For 4.x use Releases.FPML.getReleaseForVersion, for 5.x try to pick confirmation view
        if (version.startsWith("4-")) return Releases.FPML.getReleaseForVersion(version);
        // for 5.x choose confirmation view release
        try {
            String methodName = "R" + version.replace('-', '_') + "_CONFIRMATION"; // not used
        } catch (Exception ex) {}
        // common mapping
        switch (version) {
            case "5-0": return Releases.R5_0_CONFIRMATION;
            case "5-1": return Releases.R5_1_CONFIRMATION;
            case "5-2": return Releases.R5_2_CONFIRMATION;
            case "5-3": return Releases.R5_3_CONFIRMATION;
            case "5-4": return Releases.R5_4_CONFIRMATION;
            case "5-5": return Releases.R5_5_CONFIRMATION;
            case "5-6": return Releases.R5_6_CONFIRMATION;
            case "5-7": return Releases.R5_7_CONFIRMATION;
            case "5-8": return Releases.R5_8_CONFIRMATION;
            case "5-9": return Releases.R5_9_CONFIRMATION;
            case "5-10": return Releases.R5_10_CONFIRMATION;
            case "5-11": return Releases.R5_11_CONFIRMATION;
            case "5-12": return Releases.R5_12_CONFIRMATION;
            case "5-13": return Releases.R5_13_CONFIRMATION;
        }
        return null;
    }

    private static Conversion findAndInstantiateConversion(String fromVersion, String toVersion) {
        // Inspect Conversions inner classes and instantiate looking for matching source/target versions
        Class<?>[] inners = Conversions.class.getDeclaredClasses();
        for (Class<?> cls : inners) {
            if (!Conversion.class.isAssignableFrom(cls)) continue;
            try {
                Object obj = cls.getDeclaredConstructor().newInstance();
                Conversion conv = (Conversion) obj;
                Release s = conv.getSourceRelease();
                Release t = conv.getTargetRelease();
                if (s != null && t != null && s.getVersion().equals(fromVersion) && t.getVersion().equals(toVersion)) {
                    return conv;
                }
            } catch (NoSuchMethodException ns) {
                // skip
            } catch (Throwable e) {
                // ignore instantiation problems
            }
        }
        return null;
    }

    private static void ensureR5ConversionRegistration() {
        try {
            // instantiate the known R5 confirmation conversions to ensure they are registered
            new Conversions.R5_4__R5_5_CONFIRMATION();
            new Conversions.R5_5__R5_6_CONFIRMATION();
            new Conversions.R5_6__R5_7_CONFIRMATION();
            new Conversions.R5_7__R5_8_CONFIRMATION();
            new Conversions.R5_8__R5_9_CONFIRMATION();
            new Conversions.R5_9__R5_10_CONFIRMATION();
            new Conversions.R5_10__R5_11_CONFIRMATION();
            new Conversions.R5_11__R5_12_CONFIRMATION();
            new Conversions.R5_12__R5_13_CONFIRMATION();
        }
        catch (Throwable t) {
            // ignore; this is defensive — if they are already registered this may duplicate or do nothing
            System.err.println("Warning: could not instantiate some R5 conversion classes: " + t.getMessage());
        }
    }

    public static void printXml(Document doc) {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();

            // Optional pretty-print
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            // If your JDK uses Xalan, this controls indent width:
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            // Control XML declaration and encoding
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");

            transformer.transform(new DOMSource(doc), new StreamResult(System.out));
            System.out.println(); // newline after the output
        } catch (Exception e) {
            throw new RuntimeException("Failed to print XML document", e);
        }
    }

}
