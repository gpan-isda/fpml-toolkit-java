@echo off
:: Validate5-12.bat – validate FpML 5-12 confirmation examples
:: Run from the project root (one level above misc-fpml)
pushd "%~dp0.."
java -cp "handcoded.jar;lib/xml-apis.jar;lib/xercesImpl.jar" ^
    demo.com.handcoded.fpml.Validate ^
    -catalog files-fpml/catalog-fpml-5-12.xml ^
    -schemaOnly ^
    files-fpml/examples/fpml5-12/confirmation
pause
popd

