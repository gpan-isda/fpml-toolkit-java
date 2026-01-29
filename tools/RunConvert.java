public class RunConvert {
    public static void main(String[] args) throws Exception {
        System.out.println("RunConvert: starting conversion via Metagenerator.runConvertCodelist");
        String codelistDir = "tools\\codelist_enhanced_metadata_2_23\\codelist_enhanced_metadata_2_23\\codelist";
        String outFile = "files-fpml\\data\\schemes5-13.xml";
        boolean overwrite = true;
        boolean recursive = true;
        String setOfSchemes = null;
        boolean preferCanonical = false;
        Metagenerator.runConvertCodelist(codelistDir, outFile, overwrite, recursive, setOfSchemes, preferCanonical);
        System.out.println("RunConvert: finished");
    }
}

