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
package io.neebu.apps.core;

import com.amilesend.mediainfo.MediaInfo;
import com.amilesend.mediainfo.type.StreamType;
import io.neebu.apps.conn.TmdbApiClient;
import io.neebu.apps.core.entities.Constants;
import io.neebu.apps.core.models.Media;
import io.neebu.apps.pojos.CollectionType;
import io.neebu.apps.pojos.TmdbMovie;
import io.neebu.apps.pojos.TmdbTitle;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.neebu.apps.core.entities.Constants.*;

/**
 * The Class Utils.
 *
 * @author Manuel Laggner / Myron Boyle
 */
public class CollectionUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(CollectionUtils.class.getName());

    public static List<String> getStreamingServices() {
        return Arrays.stream(Constants.OTT_PLATFORMS.split(",")).toList();
    }

    public static boolean isREMUX(String fileLabel) {
        return (fileLabel.toUpperCase().contains("REMUX") || fileLabel.toUpperCase().contains(".BD50") ||
                fileLabel.toUpperCase().contains("BDMV"));
    }

    public static String getStreamingSource(String fileLabel) {
        for (String ottAbbrevation : getStreamingServices()) {
            if (fileLabel.toUpperCase().contains("." + ottAbbrevation.toUpperCase() + ".")) {
                return ottAbbrevation;
            }
        }
        return WEB_DL_DEFAULT;
    }


    public static String[] detectTitleSeasonAndEpisdoe(String filename){

        String[] ret = {"","",""};

        String pattern = "^(.+?)[. _-]+[Ss](\\d{1,2})[Ee](\\d{1,2}).*\\.[^.]+$";
        Pattern r = Pattern.compile(pattern);
        Matcher m = r.matcher(filename);

        if (m.find()) {
            ret[0] = m.group(1).replace('.', ' ').replace('_', ' ').trim();
            ret[1] = m.group(2);
            ret[2] = m.group(3);
        }

        return ret;
    }

    public static String[] detectCleanTitleAndYear(String filename) {
        String[] ret = {"", ""};
        // use trace to not remove logging completely (function called way to often on multi movie dir parsing)
        LOGGER.trace("Parse filename for title: " + filename);

        if (filename == null || filename.isEmpty()) {
            LOGGER.warn("Filename empty?!");
            return ret;
        }

        filename = filename.replaceAll("\\{tmdb-\\d+\\}", "");
        // remove extension (if found) and split (keep var)
        String fname = filename.replaceFirst("\\.\\w{2,4}$", "");
        // replaces any resolution 1234x1234 (must start and end with a non-word (else too global)
        fname = fname.replaceFirst("(?i)(" + DELIMITER + ")\\d{3,4}x\\d{3,4}" + "(" + DELIMITER + "|$)", "$1");
        // replace FPS specific words (must start with a non-word (else too global)
        for (String cw : CLEANWORDS) {
            fname = fname.replaceFirst("(?i)(" + DELIMITER + ")" + cw, "$1");
        }

        LOGGER.trace("--------------------");
        LOGGER.trace("IN: {}", fname);

        // try the badwords on the whole term (to apply regular expressions which apply on the whole term)
        String savedFname = fname;

        // do not clean the whole term!
        if (StringUtils.isBlank(fname)) {
            // revert using badwords
            fname = savedFname;
        }

        // Get [optionals] delimited
        List<String> opt = new ArrayList<>();
        Pattern p = Pattern.compile("\\[(.*?)\\]");
        Matcher m = p.matcher(fname);
        while (m.find()) {
            LOGGER.trace("OPT: {}", m.group(1));
            String[] o = StringUtils.split(m.group(1), DELIMITER);
            opt.addAll(Arrays.asList(o));
            fname = fname.replace(m.group(), ""); // remove complete group from name
        }
        LOGGER.trace("ARR: {}", opt);

        // detect OTR recordings - at least with that special pattern
        p = Pattern.compile(".*?(_\\d{2}\\.\\d{2}\\.\\d{2}[_ ]+\\d{2}\\-\\d{2}\\_).*"); // like _12.11.17_20-15_
        m = p.matcher(fname);
        if (m.matches() && m.start(1) > 10) {
            // start at some later point, not that if pattern is first
            LOGGER.trace("OTR: {}", m.group(1));
            fname = fname.substring(0, m.start(1));
        }

        // parse good filename
        String[] s = StringUtils.split(fname, DELIMITER);
        if (s.length == 0) {
            s = opt.toArray(new String[opt.size()]);
        }
        int firstFoundStopwordPosition = s.length;

        // iterate over all splitted items
        for (int i = 0; i < s.length; i++) {
            // search for stopword position
            for (String stop : HARD_STOPWORDS) {
                if (s[i].equalsIgnoreCase(stop)) {
                    s[i] = ""; // delete stopword
                    // remember lowest position, but not lower than 2!!!
                    if (i < firstFoundStopwordPosition && i >= 2) {
                        firstFoundStopwordPosition = i;
                    }
                }
            }

        }

        // scan backwards - if we have at least 1 token, and the last one is a 4 digit, assume year and remove
        int yearPosition = -1;
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        String year = "";
        for (int i = s.length - 1; i > 0; i--) {
            if (s[i].matches("\\d{4}")) {
                int parsedYear = Integer.parseInt(s[i]);
                if (parsedYear > 1900 && parsedYear < currentYear + 5) {
                    // well, limit the year a bit...
                    LOGGER.trace("removed token '" + s[i] + "'- seems to be year");
                    year = s[i];
                    s[i] = "";
                    // remember the year position
                    yearPosition = i;
                    break;
                }
            }
        }
        if (year.isEmpty()) {
            // parse all optional tags for it
            for (String o : opt) {
                if (o.matches("\\d{4}")) {
                    int parsedYear = Integer.parseInt(o);
                    if (parsedYear > 1900 && parsedYear < currentYear + 5) {
                        year = String.valueOf(parsedYear);
                        LOGGER.trace("found possible year: " + o);
                    }
                }
            }
        }

        // iterate over all splitted items (if we found a year, start from that position)
        int start = yearPosition > 0 ? yearPosition : 0;
        for (int i = start; i < s.length; i++) {
            // search for stopword position
            for (String stop : SOFT_STOPWORDS) {
                if (s[i].equalsIgnoreCase(stop)) {
                    s[i] = ""; // delete stopword
                    // remember lowest position, but not lower than 2!!!
                    if (i < firstFoundStopwordPosition && i >= 2) {
                        firstFoundStopwordPosition = i;
                    }
                }
            }
        }

        // rebuild string, respecting bad words
        StringBuilder name = new StringBuilder();
        // if the stopword position is lower than the year position, build the title up to the year position
        int end = firstFoundStopwordPosition;
        if (yearPosition > 0) {
            end = Math.min(firstFoundStopwordPosition, yearPosition);
        }
        for (int i = 0; i < end; i++) {
            if (!s[i].isEmpty()) {
                String word = s[i];
                // roman characters such as "Part Iv" should not be camel-cased
                switch (word.toUpperCase(Locale.ROOT)) {
                    case "I":
                    case "II":
                    case "III":
                    case "IV":
                    case "V":
                    case "VI":
                    case "VII":
                    case "VIII":
                    case "IX":
                    case "X":
                        name.append(word.toUpperCase(Locale.ROOT)).append(" ");
                        break;

                    default:
                        // name.append(StrgUtils.capitalizeFully(word)).append(" "); // make CamelCase
                        // NOPE - import it 1:1
                        name.append(word).append(" ");
                        break;
                }
            }
        }

        if (name.isEmpty()) {
            ret[0] = fname;
        } else {
            ret[0] = name.toString().strip();
        }
        ret[1] = year.strip();
        LOGGER.trace("Movie title should be: " + ret[0] + ", from" + ret[1]);

        return ret;
    }

    private static boolean isFileTypeVideo(Path path) {

        String[] mediaExtensions = new String[]{"mp4", "mkv", "avi"};

        String fileName = path.getFileName().toString();

        int lastIndexOfDot = fileName.lastIndexOf(".");
        if (lastIndexOfDot > 0) {
            String extension = fileName.substring(lastIndexOfDot + 1);
            for (String ext : mediaExtensions) {
                if (extension.equalsIgnoreCase(ext)) {
                    return true;
                }
            }
        }
        return false;

    }

    public static List<Path> listFilesRecursively(Path directory) {
        List<Path> fileNames = new ArrayList<>();
        try {
            Files.walk(directory)
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        return Constants.VIDEO_EXTENSIONS.stream().anyMatch(name::endsWith);
                    })
                    .forEach(file -> fileNames.add(file.toAbsolutePath()));
        } catch (Exception e) {
            LOGGER.error("Error listing files recursively in {}: {}", directory, e.getMessage(), e);
        }

        if (fileNames.isEmpty()) {
            LOGGER.warn("No video files found in {}", directory);
        }

        fileNames.sort(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()));
        return fileNames;
    }

    public static List<Path> listFilesAndDirs(Path directory) {
        List<Path> fileNames = new ArrayList<>();
        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(directory)) {
            for (Path path : directoryStream) {
                if (isFileTypeVideo(path)) {
                    fileNames.add(path.toAbsolutePath());
                }
            }
        } catch (Exception e) {
            LOGGER.error("error on listFilesAndDirs {}", e.getMessage());
        }
        if (fileNames.isEmpty()) {
            LOGGER.warn("Tried to list {}, but it was empty?!", directory);
        }

        // return sorted
        Collections.sort(fileNames);

        return fileNames;
    }

    public static TmdbMovie detectTmdbId(String text, String apiKey) throws Exception {
        TmdbMovie tmdbMovie = new TmdbMovie();
        if (StringUtils.isNotBlank(text)) {
            Matcher matcher = TMDB_ID_PATTERN.matcher(text);
            if (matcher.find() && matcher.groupCount() >= 2) {
                try {
                    TmdbTitle tmdbTitle = TmdbApiClient.getMovieByTmdbId(apiKey, "rui",123131);// Integer.parseInt(matcher.group(2)));
                    tmdbMovie.setTmdbId(tmdbTitle.getTmdbId());
                } catch (Exception e) {
                    LOGGER.trace("Could not parse TMDB id - " + e.getMessage());
                }
            } else {
                TmdbTitle tmdbTitle = TmdbApiClient.getMovieByTmdbId(apiKey, "rui",123131);// Integer.parseInt(matcher.group(2)));
                tmdbMovie.setTmdbId(tmdbTitle.getTmdbId());
                //tmdbMovie = TmdbApiClient.searchTmdbMovie(apiKey, detectCleanTitleAndYear(text)[0], Integer.valueOf(detectCleanTitleAndYear(text)[1]));
            }
        }
        return tmdbMovie;
    }


    public static int blur(int value) {
        return value + (value / 100);
    }

    public static String hdrFormat(MediaInfo myVideo) {
        // detect them combined!
        String hdrFormat = detectHdrFormat(myVideo.get(StreamType.Video, 0, "HDR_Format") + " / " + myVideo.get(StreamType.Video, 0, "HDR_Format_String") + " / " + myVideo.get(StreamType.Video, 0, "HDR_Format_Compatibility"));

        if (StringUtils.isBlank(hdrFormat)) {
            // no HDR format found? try another mediainfo field
            hdrFormat = detectHdrFormat(myVideo.get(StreamType.Video, 0, "transfer_characteristics"));
        }
        if (StringUtils.isBlank(hdrFormat)) {
            // STILL no HDR format found? check color space
            String col = myVideo.get(StreamType.Video, 0, "colour_primaries");
            if (col.contains("2100")) {
                hdrFormat = "HDR";
            }
        }
        if (StringUtils.isBlank(hdrFormat)) {
            // STILL no HDR format found? check known HDR transfer protocols
            String trans = myVideo.get(StreamType.Video, 0, "transfer_characteristics");
            if (trans.contains("2100") || trans.equals("PQ") || trans.equals("HLG")) {
                hdrFormat = "HDR";
            }
        }
        return hdrFormat;
    }

    public static String detectHdrFormat(String source) {
        source = source.toLowerCase(Locale.ROOT);
        ArrayList<String> ret = new ArrayList<>();

        if (source.contains("dolby vision")) {
            ret.add("DV");
        }
        if (source.contains("hlg")) {
            ret.add("HLG");
        }
        if (source.contains("2094") || source.contains("hdr10+")) {
            ret.add("HDR");
        }
        source = source.replaceAll("hdr10\\+", "");
        if ((source.contains("2086") || source.contains("hdr10"))) {
            ret.add("HDR");
        }

        return String.join(".", new HashSet<>(ret));
    }


    public static boolean stringContainsItemFromList(String inputStr, String[] items) {
        return Arrays.stream(items).anyMatch(inputStr::contains);
    }

    public static String[] parseName(MediaInfo myVideo) {
        for (int par = 0; par < myVideo.getStreamCount(StreamType.Audio); par++) {
            if (!myVideo.get(StreamType.Audio, par, "Title").toUpperCase().contains("COMMENT")
                    && !myVideo.get(StreamType.Audio, par, "Title").toUpperCase().contains("COMPATIBILITY")
            ) {
                String language = myVideo.get(StreamType.Audio, par, "Language");
                String[] allowedLanguages = new String[]{"en", "hi", "gu", "te", "ta", "ko", "ja", "zh", "mr"};
                if (stringContainsItemFromList(language, allowedLanguages) || language.isBlank()) {
                    String formatProfile = myVideo.get(StreamType.Audio, par, "Format_Profile");
                    String formatCommercial = coalesce(myVideo.get(StreamType.Audio, par, "Format_Commercial"),
                            myVideo.get(StreamType.Audio, par, "Format_Commercial_IfAny"),
                            myVideo.get(StreamType.Audio, par, "Format"));
                    String formatAdditionalFeatures = myVideo.get(StreamType.Audio, par, "Format_AdditionalFeatures");

                    if (formatCommercial.contains("DTS-HD") && formatAdditionalFeatures.equals("XLL X")) {
                        return new String[]{"DTS-X", getChannels(myVideo.get(StreamType.Audio, par, "Channels"))};
                    }
                    if (formatCommercial.contains("DTS-") && formatAdditionalFeatures.contains("ES")) {
                        return new String[]{"DTS-ES", getChannels(myVideo.get(StreamType.Audio, par, "Channels"))};
                    }
                    if (formatCommercial.equals("MPEG Audio") && formatProfile.contains("Layer 3")) {
                        return new String[]{"MP3", getChannels(myVideo.get(StreamType.Audio, par, "Channels"))};
                    }
                    if (formatCommercial.contains("AAC")) {
                        return new String[]{"AAC", getChannels(myVideo.get(StreamType.Audio, par, "Channels"))};
                    }
                    if (formatCommercial.equals("Dolby Digital")) {
                        return new String[]{"DD", getChannels(myVideo.get(StreamType.Audio, par, "Channels"))};
                    }
                    if (formatCommercial.equals("DTS-HD Master Audio")) {
                        return new String[]{"DTS-HD.MA", getChannels(myVideo.get(StreamType.Audio, par, "Channels"))};
                    }
                    if (formatCommercial.equals("DTS-HD High Resolution Audio")) {
                        return new String[]{"DTS-HR", getChannels(myVideo.get(StreamType.Audio, par, "Channels"))};
                    }
                    if (formatCommercial.equals("Dolby Digital Plus")) {
                        return new String[]{"DD+", getChannels(myVideo.get(StreamType.Audio, par, "Channels"))};
                    }
                    if (formatCommercial.equals("Dolby Digital Plus with Dolby Atmos")) {
                        return new String[]{"DD+.Atmos", getChannels(myVideo.get(StreamType.Audio, par, "Channels"))};
                    }
                    if (formatCommercial.equals("Dolby TrueHD with Dolby Atmos")) {
                        return new String[]{"TrueHD.Atmos", getChannels(myVideo.get(StreamType.Audio, par, "Channels"))};
                    }
                    if (formatCommercial.equals("Dolby TrueHD")) {
                        return new String[]{"TrueHD", getChannels(myVideo.get(StreamType.Audio, par, "Channels"))};
                    }
                    if (formatCommercial.equals("DTS-HD MA + IMAX Enhanced")) {
                        return new String[]{"IMAX.Enhanced.DTS-HD.MA", getChannels(myVideo.get(StreamType.Audio, par, "Channels"))};
                    }
                    return new String[]{formatCommercial, getChannels(myVideo.get(StreamType.Audio, par, "Channels"))};
                }
            }
        }
        return null;
    }

    public static String getChannels(String channels) {
        int channel = Integer.parseInt(channels);
        if (channel > 2) {
            return (channel - 1) + ".1";
        } else {
            return (channel) + ".0";
        }
    }

    public static String coalesce(String... values) {
        for (String value : values) {
            if (!StringUtils.isBlank(value)) {
                return value;
            }
        }
        return null; // If all values are null
    }

    public static String removeIllegalCharacters(String name) {
        name = StringUtils.stripAccents(name).replaceAll("[^\\p{ASCII}]", " ").replace("&", "and");
        name = name.replace(" - ", "-").replace(". ", ".").replaceAll("'", "");
        name = name.replaceAll("[\\\\/:*?\"<>|,!]", " ").trim().replaceAll("\\s+", ".").replaceAll("\\.+$", "");
        return name;
    }

    public static Map<String, Long> getAddDeleteSkipSummary(List<CollectionManager> fileList) {
        return fileList.stream().map(CollectionManager::getAddOrDeleteOrSkip).toList().stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    public static Map<Boolean, Long> getRenamedSummary(List<CollectionManager> fileList) {
        return fileList.stream().map(CollectionManager::getRenameRequired).toList().stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    public static TmdbTitle getTmdbTitleInfo(CollectionType collectionType, AppProperties appProperties, Media mediaFile) throws Exception {
        return mediaFile.isHasTmdbId() ?
                TmdbApiClient.getMovieByTmdbId(appProperties.getTmdbApiKey(),appProperties.getTmdbApiUri(), Integer.valueOf(mediaFile.getFileTmdbId()))
                : TmdbApiClient.getTmdbId(appProperties.getTmdbApiKey(),appProperties.getTmdbApiUri(),collectionType,mediaFile.getName(),mediaFile.getReleaseYear());
    }

    public static String getCollectionAction(String fileName, Boolean existInDatabase, Boolean existInPath){
        String result = "SKIP";

        if(existInDatabase && !existInPath){
            result = "DELETE";
        } else if(!existInDatabase && existInPath){
            result = "ADD";
        }
        return result;
    }


}
