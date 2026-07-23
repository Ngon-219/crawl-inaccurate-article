package org.inaccurate.crawler.filter;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.parse.ParseFilter;
import org.apache.stormcrawler.parse.ParseResult;
import org.w3c.dom.DocumentFragment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class DynamicShuntingFilter extends ParseFilter {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicShuntingFilter.class);
    private int minTextLength = 200;

    @Override
    public void configure(Map<String, Object> stormConf, JsonNode filterParams) {
        if (filterParams != null && filterParams.has("minTextLength")) {
            this.minTextLength = filterParams.get("minTextLength").asInt();
        }
    }

    @Override
    public void filter(String url, byte[] content, DocumentFragment doc, ParseResult parseResult) {
        String text = parseResult.get(url).getText();
        Metadata metadata = parseResult.get(url).getMetadata();

        if ("true".equals(metadata.getFirstValue("needs_headless"))) {
            return;
        }

        if (text == null || text.trim().length() < minTextLength) {
            LOG.warn("Phát hiện trang CSR trống (Độ dài chữ: {}). Chuyển hướng sang Playwright cho URL: {}",
                    (text != null ? text.trim().length() : 0), url);

            metadata.setValue("needs_headless", "true");

            metadata.setValue("is_temporary_error", "true");

            parseResult.get(url).setText("");
        }
    }
}