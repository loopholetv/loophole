package io.github.loopholetv.data.xmltv

import java.io.IOException

/** Thrown when an XMLTV document inflates to more bytes than the parser allows. */
class XmltvTooLargeException(limit: Long) : IOException("XMLTV document exceeds $limit bytes")
