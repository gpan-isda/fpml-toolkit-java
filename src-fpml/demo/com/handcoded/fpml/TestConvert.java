// filepath: c:\Users\gpandey\OneDrive - ISDA\Documents\Projects\FpML\fpml-toolkit-java\src-fpml\demo\com\handcoded\fpml\TestConvert.java
// Test conversion demo - creates converted files and verifies the result release

package demo.com.handcoded.fpml;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.w3c.dom.Document;

import com.handcoded.fpml.DefaultHelper;
import com.handcoded.fpml.Releases;
import com.handcoded.framework.Option;
import com.handcoded.meta.Conversion;
import com.handcoded.meta.ConversionException;
import com.handcoded.meta.Release;
import com.handcoded.meta.Specification;
import com.handcoded.xml.XmlUtility;
import com.handcoded.xml.writer.NestedWriter;

/**
 * Small test/demo program that converts FpML files to a target release and
 * verifies the converted document's release matches the requested target.
 *
 * Usage: TestConvert -target <version> -output <dir> files...
 */
public final class TestConvert extends Application
{
    public static void main (String [] arguments)
    {
        new TestConvert ().run (arguments);
    }

    @Override
    protected void startUp ()
    {
        super.startUp ();

        if (!targetOption.isPresent ()) {
            logger.severe ("No target version specified.");
            System.exit (1);
        }

        if (!outputOption.isPresent ()) {
            logger.severe ("No output directory was specified.");
            System.exit (1);
        }

        directory = new File (outputOption.getValue ());
        if (directory.exists ()) {
            if (!directory.isDirectory ()) {
                logger.severe ("The output target exists and is not a directory");
                System.exit (1);
            }
        }
        else {
            if (!directory.mkdir ()) {
                logger.severe ("Failed to create output directory");
                System.exit (1);
            }
        }
    }

    @Override
    protected void execute ()
    {
        String []    arguments = findFiles (getArguments ());

        if (arguments.length == 0) {
            logger.info ("No input files specified. Nothing to do.");
            setFinished (true);
            return;
        }

        for (String filename : arguments) {
            File      file     = new File (filename);
            Document  document = XmlUtility.nonValidatingParse (file);

            System.out.println (">> " + filename);

            Release source = Specification.releaseForDocument (document);
            Release target = Releases.compatibleRelease (document, targetOption.getValue ());

            if (target == null) {
                System.out.println ("!! No compatible target FpML release");
                continue;
            }

            Conversion    conversion = Conversion.conversionFor (source, target);

            if (conversion == null) {
                System.out.println ("!! No conversion path exists to the target version");
                continue;
            }

            try {
                Document newDocument = conversion.convert (document, new DefaultHelper ());

                try {
                    OutputStream    stream = new FileOutputStream (new File (directory, file.getName ()));

                    new NestedWriter (stream).write (newDocument);
                    stream.close ();
                }
                catch (Exception error) {
                    logger.log (Level.SEVERE, "Exception while writing converted XML document", error);
                }

                // Verify the converted document's release
                Release resultRelease = Specification.releaseForDocument (newDocument);
                if (resultRelease != null && resultRelease.equals (target)) {
                    System.out.println ("++ Conversion succeeded and matches target: " + target);
                }
                else {
                    System.out.println ("!! Conversion produced a document that does not match the target release");
                    if (resultRelease != null) {
                        System.out.println ("   Detected release: " + resultRelease);
                    }
                }
            }
            catch (ConversionException error) {
                logger.log (Level.SEVERE, "FpML document conversion failed", error);
                continue;
            }
        }
        setFinished (true);
    }

    @Override
    protected String describeArguments ()
    {
        return (" files or directories ...");
    }

    private static Logger    logger
        = Logger.getLogger ("demo.com.handcoded.fpml.TestConvert");

    private Option            targetOption
        = new Option ("-target", "The target version of FpML", "version");

    private Option            outputOption
        = new Option ("-output", "The output directory", "directory");

    private File            directory;

    private TestConvert ()
    { }
}

