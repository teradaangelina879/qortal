package org.qortal.api.resource;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.qortal.storage.DataFile;
import org.qortal.utils.ZipUtils;


@Path("/site")
public class WebsiteResource {

    private static final Logger LOGGER = LogManager.getLogger(WebsiteResource.class);

    @GET
    @Path("{resource}")
    public Response getResourceIndex(@PathParam("resource") String resourceId) {
        return this.get(resourceId, "/");
    }

    @GET
    @Path("{resource}/{path:.*}")
    public Response getResourcePath(@PathParam("resource") String resourceId, @PathParam("path") String inPath) {
        return this.get(resourceId, inPath);
    }

    private Response get(String resourceId, String inPath) {
        if (!inPath.startsWith(File.separator)) {
            inPath = File.separator + inPath;
        }

        String tempDirectory = System.getProperty("java.io.tmpdir");
        String destPath = tempDirectory + "qortal-sites" + File.separator + resourceId;
        String unzippedPath = destPath + File.separator + "data";

        if (!Files.exists(Paths.get(unzippedPath))) {

            // Load file
            DataFile dataFile = DataFile.fromBase58Digest(resourceId);
            if (dataFile == null || !dataFile.exists()) {
                LOGGER.info("Unable to validate complete file hash");
                return Response.serverError().build();
            }

            String newHash = dataFile.base58Digest();
            LOGGER.info("newHash: {}", newHash);
            if (!dataFile.base58Digest().equals(resourceId)) {
                LOGGER.info("Unable to validate complete file hash");
                return Response.serverError().build();
            }

            try {
                ZipUtils.unzip(dataFile.getFilePath(), destPath);
            } catch (IOException e) {
                LOGGER.info("Unable to unzip file");
            }
        }

        try {
            String filename = this.getFilename(unzippedPath, inPath);
            byte[] data = Files.readAllBytes(Paths.get(unzippedPath + File.separator + filename)); // TODO: limit file size that can be read into memory
            data = this.replaceRelativeLinks(filename, data, resourceId);
            return Response.ok(data).build();
        } catch (IOException e) {
            LOGGER.info("Unable to serve file at path: {}", inPath);
        }

        return Response.serverError().build();
    }

    private String getFilename(String directory, String userPath) {
        if (userPath == null || userPath.endsWith("/") || userPath.equals("")) {
            // Locate index file
            List<String> indexFiles = this.indexFiles();
            for (String indexFile : indexFiles) {
                String filePath = directory + File.separator + indexFile;
                if (Files.exists(Paths.get(filePath))) {
                    return userPath + indexFile;
                }
            }
        }
        return userPath;
    }

    /**
     * Find relative links and prefix them with the resource ID, using Jsoup
     * @param path
     * @param data
     * @param resourceId
     * @return The data with links replaced
     */
    private byte[] replaceRelativeLinks(String path, byte[] data, String resourceId) {
        if (this.isHtmlFile(path)) {
            String fileContents = new String(data);
            Document document = Jsoup.parse(fileContents);

            Elements href = document.select("[href]");
            for (Element element : href)  {
                String elementHtml = element.attr("href");
                if (elementHtml.startsWith("/") && !elementHtml.startsWith("//"))    {
                    element.attr("href", "/site/" +resourceId + element.attr("href"));
                }
            }
            Elements src = document.select("[src]");
            for (Element element : src)  {
                String elementHtml = element.attr("src");
                if (elementHtml.startsWith("/") && !elementHtml.startsWith("//"))    {
                    element.attr("src", "/site/" +resourceId + element.attr("src"));
                }
            }
            return document.html().getBytes();
        }
        return data;
    }

    private List<String> indexFiles() {
        List<String> indexFiles = new ArrayList<>();
        indexFiles.add("index.html");
        indexFiles.add("index.htm");
        indexFiles.add("default.html");
        indexFiles.add("default.htm");
        indexFiles.add("home.html");
        indexFiles.add("home.htm");
        return indexFiles;
    }

    private boolean isHtmlFile(String path) {
        if (path.endsWith(".html") || path.endsWith(".htm")) {
            return true;
        }
        return false;
    }

}
