# Samples

This directory contains sample / example code that is **not** part of the core
FpML toolkit.

## src-acme

A minimal worked example of how to extend the toolkit to support a
consumer-defined FpML namespace (`com.handcoded.acme`).

It shows how to:
* Define a new `Releases` class for your namespace
* Wire custom release metadata into the toolkit bootstrap

To build the sample add `samples/src-acme` to your Java source path alongside
the core `src-fpml` and `src-core` trees.

