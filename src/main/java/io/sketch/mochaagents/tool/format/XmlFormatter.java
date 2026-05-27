// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool.format;

import java.io.StringReader;
import java.io.StringWriter;
import javax.xml.transform.*;
import javax.xml.transform.stream.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** XML formatter — pretty-print using JDK Transformer. Falls back if invalid XML. */
public class XmlFormatter implements CodeFormatter {

    private static final Logger log = LoggerFactory.getLogger(XmlFormatter.class);

    @Override
    public String format(String source) {
        try {
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            var sw = new StringWriter();
            t.transform(new StreamSource(new StringReader(source)), new StreamResult(sw));
            return sw.toString();
        } catch (Exception e) {
            log.debug("Invalid XML, returning as-is: {}", e.getMessage());
            return source.trim() + "\n";
        }
    }
}
