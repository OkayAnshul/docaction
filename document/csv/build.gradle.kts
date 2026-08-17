plugins {
    alias(libs.plugins.docaction.jvm.library)
}

/*
 * Pure JVM, like `:document:spreadsheet` — a delimited text file needs no Android at all, and
 * keeping it off the classpath is what lets the whole format be tested without an emulator.
 *
 * It depends on the spreadsheet module rather than duplicating it: a CSV *is* a one-sheet
 * workbook, and `SpreadsheetSchedules` already knows how to find stacked sections in one,
 * split them, and build entries. Reimplementing that here would be a second engine to keep
 * in step with the first.
 */
dependencies {
    api(project(":domain"))
    api(project(":document:spreadsheet"))
}
