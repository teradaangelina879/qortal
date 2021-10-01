//
// Code originally written by memorynotfound
// https://memorynotfound.com/java-7z-seven-zip-example-compress-decompress-file/
// Modified Sept 2021 by Qortal Core dev team
//

package org.qortal.utils;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;

import java.io.*;

public class SevenZ {

    private SevenZ() {

    }

    public static void compress(String outputPath, File... files) throws IOException {
        try (SevenZOutputFile out = new SevenZOutputFile(new File(outputPath))){
            for (File file : files){
                addToArchiveCompression(out, file, ".");
            }
        }
    }

    public static void decompress(String in, File destination) throws IOException {
        SevenZFile sevenZFile = new SevenZFile(new File(in));
        SevenZArchiveEntry entry;
        while ((entry = sevenZFile.getNextEntry()) != null){
            if (entry.isDirectory()){
                continue;
            }
            File curfile = new File(destination, entry.getName());
            File parent = curfile.getParentFile();
            if (!parent.exists()) {
                parent.mkdirs();
            }

            FileOutputStream out = new FileOutputStream(curfile);
            byte[] b = new byte[8192];
            int count = 0;
            while ((count = sevenZFile.read(b)) > 0) {
                out.write(b, 0, count);
            }
            out.close();
        }
    }

    private static void addToArchiveCompression(SevenZOutputFile out, File file, String dir) throws IOException {
        String name = dir + File.separator + file.getName();
        if (file.isFile()){
            SevenZArchiveEntry entry = out.createArchiveEntry(file, name);
            out.putArchiveEntry(entry);

            FileInputStream in = new FileInputStream(file);
            byte[] b = new byte[8192];
            int count = 0;
            while ((count = in.read(b)) > 0) {
                out.write(b, 0, count);
            }
            out.closeArchiveEntry();

        } else if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null){
                for (File child : children){
                    addToArchiveCompression(out, child, name);
                }
            }
        } else {
            System.out.println(file.getName() + " is not supported");
        }
    }
}
