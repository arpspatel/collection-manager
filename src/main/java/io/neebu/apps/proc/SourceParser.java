/*
 * Copyright 2012 - 2024 Manuel Laggner
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.neebu.apps.proc;

import io.neebu.apps.core.entities.DynaEnum;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The enum {@link SourceParser} - to represent all possible media sources
 * 
 * @author Manuel Laggner
 */
public class SourceParser extends DynaEnum<SourceParser> {
  private static final Comparator<SourceParser> COMPARATOR  = new MediaSourceComparator();
  private static final Comparator<SourceParser> COMP_LENGTH = new MediaSourceLengthComparator();

  // the well known and XBMC/Kodi compatible sources
  // tokens taken from http://en.wikipedia.org/wiki/Pirated_movie_release_types
  public static final SourceParser BRRIP  = new SourceParser("BRRIP", 0, "BRRip",
      "(bdrip|brrip|dbrip)");
  public static final SourceParser BLURAY      = new SourceParser("BLURAY", 1, "Bluray",
      "(bluray|blueray|bd25|bd50|bdmv|blu\\-ray)");
  public static final SourceParser TV          = new SourceParser("TV", 4, "TVRip",
      "(tv|hdtv|pdtv|dsr|dtb|dtt|dttv|dtv|hdtvrip|tvrip|dvbrip|hdrip)");
  // other sources
  public static final SourceParser WEBRIP      = new SourceParser("WEBRIP", 14, "WEBRip", "(webrip)");
  public static final SourceParser WEB_DL      = new SourceParser("WEB_DL", 15, "WEB-DL", "(web-dl|webdl|web)");
  public static final SourceParser STREAM      = new SourceParser("STREAM", 16, "Stream");
  // and our fallback
  public static final SourceParser UNKNOWN     = new SourceParser("UNKNOWN", 17, "");

  private static final String                  START_TOKEN = "[\\/\\\\ _,.()\\[\\]-]";
  private static final String                  END_TOKEN   = "([\\/\\\\ _,.()\\[\\]-]|$)";

  private final String                         title;
  private final Pattern                        pattern;
  private final Pattern                        patternWoDelim;

  private SourceParser(String enumName, int ordinal, String title) {
    this(enumName, ordinal, title, "");
  }

  private SourceParser(String enumName, int ordinal, String title, String pattern) {
    super(enumName, ordinal);
    this.title = title;
    if (StringUtils.isNotBlank(pattern)) {
      this.pattern = Pattern.compile(START_TOKEN + pattern + END_TOKEN, Pattern.CASE_INSENSITIVE);
      this.patternWoDelim = Pattern.compile("^" + pattern + "$", Pattern.CASE_INSENSITIVE);
    }
    else {
      this.pattern = null;
      this.patternWoDelim = null;
    }

    addElement();
  }

  @Override
  public String toString() {
    return title;
  }

  /**
   * get all media sources
   *
   * @return an array of all media sources
   */
  public static SourceParser[] values() {
    SourceParser[] sourceParsers = values(SourceParser.class);
    Arrays.sort(sourceParsers, COMPARATOR);
    return sourceParsers;
  }

  /**
   * returns the MediaSource if found in file name
   * 
   * @param filename
   *          the filename
   * @return the matching MediaSource or UNKNOWN
   */
  public static SourceParser parseMediaSource(String filename) {
    String fn = filename.toLowerCase(Locale.ROOT);
    SourceParser[] s = SourceParser.values();
    Arrays.sort(s, SourceParser.COMP_LENGTH);

    // convert to path, and start parsing from filename upto base directory
    try {
      Path p = Paths.get(fn);
      for (SourceParser sourceParser : s) {
        Path work = p;
        while (work != null) {
          String name = work.getName(work.getNameCount() - 1).toString();
          if (sourceParser.pattern != null && sourceParser.pattern.matcher(name).find()) {
            return sourceParser;
          }
          if (sourceParser.patternWoDelim != null && sourceParser.patternWoDelim.matcher(name).find()) {
            return sourceParser;
          }
          // maybe file? try w/o extension to better match woDelims ;)
          name = FilenameUtils.getBaseName(name);
          if (name != null && sourceParser.patternWoDelim != null && sourceParser.patternWoDelim.matcher(name).find()) {
            return sourceParser;
          }
          work = work.getParent();
        }
      }
    }
    catch (Exception e) {
      // does not work? parse as string as before
      for (SourceParser sourceParser : s) {
        if (sourceParser.pattern != null && sourceParser.pattern.matcher(filename).find()) {
          return sourceParser;
        }
      }
    }

    String ext = "";
    try {
      ext = FilenameUtils.getExtension(fn);
    }
    catch (Exception e) {
      // eg : on windows (see unit test)
      int i = filename.lastIndexOf('.');
      if (i > 0) {
        ext = filename.substring(i + 1);
      }
    }
    if (ext.equals("strm")) {
      return STREAM;
    }

    return UNKNOWN;
  }

 /**
   * Comparator for sorting our MediaSource in a localized fashion
   */
  private static class MediaSourceComparator implements Comparator<SourceParser> {
    @Override
    public int compare(SourceParser o1, SourceParser o2) {
      // toString is localized name
      if (o1.toString() == null && o2.toString() == null) {
        return 0;
      }
      if (o1.toString() == null) {
        return 1;
      }
      if (o2.toString() == null) {
        return -1;
      }
      return o1.toString().compareTo(o2.toString());
    }
  }

  /**
   * Comparator for sorting our MediaSource from longest to shortest
   */
  private static class MediaSourceLengthComparator implements Comparator<SourceParser> {
    @Override
    public int compare(SourceParser o1, SourceParser o2) {
      if (o1 == null && o2 == null) {
        return 0;
      }
      if (o1.name() == null) {
        return 1;
      }
      if (o2.name() == null) {
        return -1;
      }
      return Integer.compare(o2.name().length(), o1.name().length());
    }
  }
}
