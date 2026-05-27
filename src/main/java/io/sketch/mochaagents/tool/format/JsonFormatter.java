// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool.format;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** JSON formatter — pretty-print using Jackson. Falls back to basic formatting if invalid JSON. */
public class JsonFormatter implements CodeFormatter {

    private static final Logger log = LoggerFactory.getLogger(JsonFormatter.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public String format(String source) {
        try {
            Object tree = JSON.readTree(source);
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
        } catch (IOException e) {
            log.debug("Invalid JSON, returning as-is: {}", e.getMessage());
            return source.trim() + "\n";
        }
    }
}
