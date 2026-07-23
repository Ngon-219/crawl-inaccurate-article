package org.inaccurate.crawler;

import crawlercommons.robots.BaseRobotRules;
import org.apache.storm.Config;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.protocol.Protocol;
import org.apache.stormcrawler.protocol.ProtocolResponse;
import org.apache.stormcrawler.util.ConfUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class AutoDetectProtocol implements Protocol {

    private Protocol httpDelegate;
    private Protocol seleniumDelegate;

    private int minTextLength;
    private boolean cacheEnabled;

    private static final Map<String, Boolean> hostNeedsSelenium = new ConcurrentHashMap<>();

    private static final Pattern[] SPA_MARKERS = new Pattern[] {
            Pattern.compile("id=[\"']root[\"']"),
            Pattern.compile("id=[\"']app[\"']"),
            Pattern.compile("id=[\"']__next[\"']"),
            Pattern.compile("data-reactroot"),
            Pattern.compile("ng-app"),
            Pattern.compile("window\\.__NUXT__"),
            Pattern.compile("<noscript>[^<]*enable\\s*javascript", Pattern.CASE_INSENSITIVE)
    };

    @Override
    public void configure(Config conf) {
        String httpImpl = ConfUtils.getString(conf,
                "autodetect.http.impl",
                "org.apache.stormcrawler.protocol.okhttp.HttpProtocol");
        String seleniumImpl = ConfUtils.getString(conf,
                "autodetect.selenium.impl",
                "org.apache.stormcrawler.protocol.selenium.RemoteDriverProtocol");

        minTextLength = ConfUtils.getInt(conf, "autodetect.min.text.length", 200);
        cacheEnabled = ConfUtils.getBoolean(conf, "autodetect.cache.host", true);

        try {
            httpDelegate = (Protocol) Class.forName(httpImpl).getDeclaredConstructor().newInstance();
            httpDelegate.configure(conf);

            seleniumDelegate = (Protocol) Class.forName(seleniumImpl).getDeclaredConstructor().newInstance();
            seleniumDelegate.configure(conf);
        } catch (Exception e) {
            throw new RuntimeException("Không khởi tạo được delegate protocol", e);
        }
    }

    @Override
    public ProtocolResponse getProtocolOutput(String url, Metadata metadata) throws Exception {

        String host = extractHost(url);

        if ("true".equals(metadata.getFirstValue("protocol.use.selenium"))) {
            return seleniumDelegate.getProtocolOutput(url, metadata);
        }

        if (cacheEnabled && Boolean.TRUE.equals(hostNeedsSelenium.get(host))) {
            metadata.setValue("protocol.use.selenium", "true");
            metadata.setValue("protocol.autodetect.reason", "host_cache");
            return seleniumDelegate.getProtocolOutput(url, metadata);
        }

        ProtocolResponse httpResponse = httpDelegate.getProtocolOutput(url, metadata);

        if (looksLikeCSR(httpResponse)) {
            if (cacheEnabled) {
                hostNeedsSelenium.put(host, Boolean.TRUE);
            }
            metadata.setValue("protocol.use.selenium", "true");
            metadata.setValue("protocol.autodetect.reason", "csr_detected_fallback");

            return seleniumDelegate.getProtocolOutput(url, metadata);
        }

        if (cacheEnabled) {
            hostNeedsSelenium.putIfAbsent(host, Boolean.FALSE);
        }
        metadata.setValue("protocol.autodetect.reason", "ssr_ok");
        return httpResponse;
    }

    @Override
    public BaseRobotRules getRobotRules(String url) {
        String host = extractHost(url);
        if (cacheEnabled && Boolean.TRUE.equals(hostNeedsSelenium.get(host))) {
            return seleniumDelegate.getRobotRules(url);
        }
        return httpDelegate.getRobotRules(url);
    }

    private boolean looksLikeCSR(ProtocolResponse response) {
        if (response == null || response.getContent() == null) return false;

        int status = response.getStatusCode();
        if (status < 200 || status >= 300) return false;

        String html = new String(response.getContent(),
                java.nio.charset.StandardCharsets.UTF_8);

        String strippedText = html.replaceAll("(?s)<script.*?</script>", "")
                .replaceAll("(?s)<style.*?</style>", "")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();

        boolean tooShort = strippedText.length() < minTextLength;

        boolean hasMarker = false;
        for (Pattern p : SPA_MARKERS) {
            if (p.matcher(html).find()) { hasMarker = true; break; }
        }

        return tooShort && (hasMarker || html.length() < 2000);
    }

    private String extractHost(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    @Override
    public void cleanup() {
        try { httpDelegate.cleanup(); } catch (Exception ignored) {}
        try { seleniumDelegate.cleanup(); } catch (Exception ignored) {}
    }
}
