// The only thing that crosses into the isolated parsing process.
//
// Deliberately tiny. Bytes go in over a file descriptor, encoded text runs come back over
// one; nothing else passes, and neither side hands the other an object it has to trust.
package com.okayanshul.docaction.document.sandbox;

interface ISandboxParser {

    /**
     * Opens the PDF behind [document] for reading.
     *
     * Returns the page count, or a negative value from SandboxCodes describing why not. A
     * negative return is not an error condition to be logged — it is the ordinary answer for
     * an encrypted or damaged file, which is most of what this service exists to survive.
     */
    int open(in ParcelFileDescriptor document);

    /**
     * Writes one page's text runs into [target], encoded by DocumentCodec.
     *
     * Returns true when the page had a usable text layer. False means the caller should fall
     * back to OCR, which happens in the app process where the model lives.
     *
     * [target] is a file rather than a pipe on purpose: a page dense enough to exceed a
     * pipe buffer would deadlock the synchronous call that is filling it.
     */
    boolean readPage(int index, in ParcelFileDescriptor target);

    /** Releases the document. The process is killed anyway; this keeps a reuse honest. */
    void release();
}
