import java.nio.file.*;

public class RunEnsureDefaults {
    public static void main(String[] args) throws Exception {
        // Usage: java -cp tools RunEnsureDefaults [metaDirOrFile] [dataDir] [backup]
        Path metaPath = Paths.get("files-fpml/meta");
        String dataDir = "files-fpml/data";
        boolean backup = true;
        if (args.length >= 1) metaPath = Paths.get(args[0]);
        if (args.length >= 2) dataDir = args[1];
        if (args.length >= 3) backup = Boolean.parseBoolean(args[2]);

        System.out.println("RunEnsureDefaults: updating meta files in " + metaPath + " using dataDir=" + dataDir + " backup=" + backup);

        if (Files.isRegularFile(metaPath)) {
            // single meta file provided
            System.out.println("  updating single file: " + metaPath.getFileName());
            try {
                Metagenerator.updateMetaWithSchemes(metaPath.toString(), dataDir, backup);
            } catch (Exception ex) {
                System.err.println("  Failed to update " + metaPath + " : " + ex.getMessage());
            }
            System.out.println("RunEnsureDefaults: finished");
            return;
        }

        if (!Files.isDirectory(metaPath)) {
            System.err.println("RunEnsureDefaults: meta path not found or is not a directory: " + metaPath);
            return;
        }

        final String dd = dataDir;
        final boolean bak = backup;
        try (java.util.stream.Stream<Path> s = Files.list(metaPath)) {
            s.filter(p -> {
                String fn = p.getFileName().toString().toLowerCase();
                return fn.startsWith("fpml-5-13-") && fn.endsWith(".xml") && !fn.endsWith(".generated.xml") && !fn.endsWith(".bak");
            }).sorted().forEach(p -> {
                System.out.println("  updating " + p.getFileName());
                try {
                    Metagenerator.updateMetaWithSchemes(p.toString(), dd, bak);
                } catch (Exception ex) {
                    System.err.println("  Failed to update " + p.getFileName() + " : " + ex.getMessage());
                }
            });
        }
        System.out.println("RunEnsureDefaults: finished");
    }
}
