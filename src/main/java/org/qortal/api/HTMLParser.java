package org.qortal.api;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class HTMLParser {

    private static final Logger LOGGER = LogManager.getLogger(HTMLParser.class);

    private String linkPrefix;
    private byte[] data;

    public HTMLParser(String resourceId, String inPath, String prefix, boolean usePrefix, byte[] data) {
        String inPathWithoutFilename = inPath.substring(0, inPath.lastIndexOf('/'));
        this.linkPrefix = usePrefix ? String.format("%s/%s%s", prefix, resourceId, inPathWithoutFilename) : "";
        this.data = data;
    }

    /**
     * Find relative links and prefix them with the resource ID, using Jsoup
     * @param path
     * @return The data with links replaced
     */
    public void replaceRelativeLinks(String path) {
        if (HTMLParser.isHtmlFile(path)) {
            String fileContents = new String(data);
            Document document = Jsoup.parse(fileContents);

            Elements href = document.select("[href]");
            for (Element element : href)  {
                String elementHtml = element.attr("href");
                if (this.shouldReplaceLink(elementHtml)) {
                    String slash = (elementHtml.startsWith("/") ? "" : "/");
                    element.attr("href", this.linkPrefix + slash + element.attr("href"));
                }
            }
            Elements src = document.select("[src]");
            for (Element element : src)  {
                String elementHtml = element.attr("src");
                if (this.shouldReplaceLink(elementHtml)) {
                    String slash = (elementHtml.startsWith("/") ? "" : "/");
                    element.attr("src", this.linkPrefix + slash + element.attr("src"));
                }
            }
            Elements srcset = document.select("[srcset]");
            for (Element element : srcset)  {
                String elementHtml = element.attr("srcset").trim();
                if (this.shouldReplaceLink(elementHtml)) {
                    String[] parts = element.attr("srcset").split(",");
                    ArrayList<String> newParts = new ArrayList<>();
                    for (String part : parts) {
                        part = part.trim();
                        String slash = (elementHtml.startsWith("/") ? "" : "/");
                        String newPart = this.linkPrefix + slash + part;
                        newParts.add(newPart);
                    }
                    String newString = String.join(",", newParts);
                    element.attr("srcset", newString);
                }
            }
            Elements style = document.select("[style]");
            for (Element element : style)  {
                String elementHtml = element.attr("style");
                if (elementHtml.contains("url(")) {
                    String[] parts = elementHtml.split("url\\(");
                    String[] parts2 = parts[1].split("\\)");
                    String link = parts2[0];
                    if (link != null) {
                        link = this.removeQuotes(link);
                        if (this.shouldReplaceLink(link)) {
                            String slash = (link.startsWith("/") ? "" : "/");
                            String modifiedLink = "url('" + this.linkPrefix + slash + link + "')";
                            element.attr("style", parts[0] + modifiedLink + parts2[1]);
                        }
                    }
                }
            }
            String html = document.html();
            html = this.replaceAmpersands(html);
            this.data = html.getBytes();
        }
    }

    private String replaceAmpersands(String html) {
        return html.replace("&amp;", "&");
    }

    private boolean shouldReplaceLink(String elementHtml) {
        List<String> prefixes = new ArrayList<>();
        prefixes.add("http"); // Don't modify absolute links
        prefixes.add("//"); // Don't modify absolute links
        prefixes.add("javascript:"); // Don't modify javascript
        prefixes.add("../"); // Don't modify valid relative links
        for (String prefix : prefixes) {
            if (elementHtml.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    private String removeQuotes(String elementHtml) {
        if (elementHtml.startsWith("\"") || elementHtml.startsWith("\'")) {
            elementHtml = elementHtml.substring(1);
        }
        if (elementHtml.endsWith("\"") || elementHtml.endsWith("\'")) {
            elementHtml = elementHtml.substring(0, elementHtml.length() - 1);
        }
        return elementHtml;
    }

    public static boolean isHtmlFile(String path) {
        if (path.endsWith(".html") || path.endsWith(".htm")) {
            return true;
        }
        return false;
    }

    public byte[] getData() {
        return this.data;
    }
}
