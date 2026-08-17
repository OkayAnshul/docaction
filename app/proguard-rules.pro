# R8 configuration for the release build.
#
# Every rule below exists because the release build failed without it, or because the class
# in question is reached only by reflection and so looks unused to R8. Nothing here is
# speculative: a keep rule added "just in case" silently gives up size and, worse, hides the
# next real problem behind a wall of retained code.

# --- PdfBox-Android ---

# JPEG 2000 support is optional in PdfBox and the decoder is not a dependency of this app.
# A PDF carrying a JPX image will fail to decode that image and nothing else — we read text,
# not pictures — so warning about it is noise rather than signal.
-dontwarn com.gemalto.jp2.JP2Decoder

# PdfBox loads its font metrics, glyph lists and CMaps from resources at runtime and reaches
# them by name. Stripping them produces a parser that opens documents and then finds no text.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.tom_roush.fontbox.**

# --- ML Kit text recognition ---

# The recogniser is reached through Google Play services by name, and its result types are
# instantiated reflectively. Without these the release build opens an image, hands it to the
# recogniser, and gets an exception — which the app then reports as "we couldn't process
# this" for every photo and every scanned PDF. Debug builds are unaffected, so this fails
# only in the one configuration users actually install.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.gms.vision.** { *; }
-dontwarn com.google.mlkit.**

# --- Line numbers in crash reports ---

# Without these a stack trace from a release build is unreadable. The source file name is
# renamed away, which keeps the mapping private while the line numbers stay useful.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
