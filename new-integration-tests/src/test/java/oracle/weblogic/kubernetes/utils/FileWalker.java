// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;

import static oracle.weblogic.kubernetes.extensions.LoggedTest.logger;

/**
 * File walker utility to print file permissions and ownership.
 *
 */
public class FileWalker {

  /**
   * Main method.
   * @param args arguments
   */
  public static void main(String[] args) {
    try {
      walk(".");
    } catch (IOException ex) {
      logger.severe(ex.getMessage());
    }
  }

  /**
   * Walk a directory and print ownership and permissions info.
   *
   * @param dir directory to walk through
   * @throws IOException when file attributes reading fails.
   */
  public static void walk(String dir) throws IOException {
    File root = new File(dir);
    File[] list = root.listFiles();
    if (list == null) {
      return;
    }

    for (File f : list) {
      Path path = Paths.get(f.getAbsolutePath());
      PosixFileAttributes attrs = Files.readAttributes(path, PosixFileAttributes.class);
      String permissions = PosixFilePermissions.toString(attrs.permissions());
      String owner = attrs.owner() + " " + attrs.group();
      long size = attrs.size();
      FileTime dateModified = attrs.lastModifiedTime();
      if (f.isDirectory()) {
        //logger.info("d{0} {1} {2} {3} {4}", permissions, owner, size, dateModified, f.getAbsoluteFile());
        walk(f.getAbsolutePath());
      } else {
        logger.info("{0} {1} {2} {3} {4}", permissions, owner, size, dateModified, f.getAbsoluteFile());
      }
    }
  }

}
