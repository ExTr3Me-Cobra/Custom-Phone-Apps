# ML Kit and ZXing ship their own consumer rules. Keep Room entities' field names for export/import.
-keep class com.labelrescue.app.data.** { *; }

# DataMatrixRescue reads ZXing's package-private DataMatrix internals from a helper in ZXing's own
# package (core: com.google.zxing.datamatrix.decoder.ZxingDataMatrixAccess): keep that package
# together and unrenamed so the same-package access holds.
-keep class com.google.zxing.datamatrix.decoder.** { *; }
