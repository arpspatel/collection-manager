package io.neebu.apps.utils;

import com.amilesend.mediainfo.MediaInfo;
import com.amilesend.mediainfo.lib.MediaInfoAccessor;
import com.amilesend.mediainfo.lib.MediaInfoLibrary;
import com.amilesend.mediainfo.type.StreamType;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class MediaParserV2 {

    private static final Logger LOGGER = LoggerFactory.getLogger(MediaParserV2.class);

    private final MediaInfo mediaInfo;

    public MediaParserV2(Path filePath) throws IOException {
        LOGGER.debug("Initializing MediaParser for file: {}", filePath);
        MediaInfoLibrary library = MediaInfoLibrary.newInstance();
        MediaInfoAccessor accessor = new MediaInfoAccessor(library);
        this.mediaInfo = new MediaInfo(accessor).open(filePath.toFile());

        if (this.mediaInfo == null) {
            LOGGER.error("Failed to open media file: {}", filePath);
            throw new IOException("Could not open media file: " + filePath);
        }
        LOGGER.info("Media file loaded successfully: {}", filePath);
    }

    public String getVideoCodec() {
        String codecHint = mediaInfo.get(StreamType.Video, 0, "CodecID/Hint");
        String videoCodec = StringUtils.isBlank(codecHint)
                ? mediaInfo.get(StreamType.Video, 0, "Format")
                : codecHint;

        if (videoCodec != null && videoCodec.toLowerCase(Locale.ROOT).contains("microsoft")) {
            videoCodec = mediaInfo.get(StreamType.Video, 0, "Format");
        }

        String codecId = mediaInfo.get(StreamType.General, 0, "CodecID");

        if ("XVID".equalsIgnoreCase(codecId)) {
            videoCodec = "XVID";
        } else if ("AVC".equalsIgnoreCase(videoCodec)) {
            videoCodec = "H264";
        }

        if (videoCodec != null && videoCodec.toLowerCase(Locale.ROOT).contains("mpeg")) {
            try {
                int version = Integer.parseInt(
                        mediaInfo.get(StreamType.Video, 0, "Format_Version").replaceAll("\\D", "")
                );
                videoCodec = "MPEG" + version;
            } catch (Exception e) {
                LOGGER.debug("Could not parse MPEG version: {}", e.getMessage());
            }
        }
        return videoCodec;
    }

    public String getVideoFormat() {
        try {
            int width = Integer.parseInt(mediaInfo.get(StreamType.Video, 0, "Width"));
            int height = Integer.parseInt(mediaInfo.get(StreamType.Video, 0, "Height"));
            if (width == 0 || height == 0) return "";
            return CollectionUtils.detectResolution(width, height);
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid resolution data: {}", e.getMessage());
            return "";
        }
    }

    public String getHdrFormat() {
        String rawHdrData = String.join(" / ",
                mediaInfo.get(StreamType.Video, 0, "HDR_Format"),
                mediaInfo.get(StreamType.Video, 0, "HDR_Format_String"),
                mediaInfo.get(StreamType.Video, 0, "HDR_Format_Compatibility")
        );

        String hdrFormat = CollectionUtils.detectHdrFormat(rawHdrData);

        if (StringUtils.isBlank(hdrFormat)) {
            String transfer = mediaInfo.get(StreamType.Video, 0, "transfer_characteristics");
            hdrFormat = CollectionUtils.detectHdrFormat(transfer);

            if (StringUtils.isBlank(hdrFormat)) {
                String color = mediaInfo.get(StreamType.Video, 0, "colour_primaries");
                if (color.contains("2100") || transfer.contains("2100") ||
                        "PQ".equalsIgnoreCase(transfer) || "HLG".equalsIgnoreCase(transfer)) {
                    hdrFormat = "HDR";
                }
            }
        }
        return hdrFormat;
    }

    /** Returns a list of audio track info arrays: {codec, channels, language, title} */
    public List<String[]> getAllAudioTracks() {
        List<String[]> audioTracks = new ArrayList<>();
        int audioStreams = mediaInfo.getStreamCount(StreamType.Audio);

        for (int i = 0; i < audioStreams; i++) {
            String title = mediaInfo.get(StreamType.Audio, i, "Title");
            if (title == null) title = "";
            String titleUpper = title.toUpperCase(Locale.ROOT);
            if (titleUpper.contains("COMMENT") || titleUpper.contains("COMPATIBILITY")) {
                continue; // skip comment or compatibility tracks
            }

            String language = mediaInfo.get(StreamType.Audio, i, "Language");
            if (language == null) language = "";

            String formatProfile = mediaInfo.get(StreamType.Audio, i, "Format_Profile");
            String formatCommercial = CollectionUtils.coalesce(
                    mediaInfo.get(StreamType.Audio, i, "Format_Commercial"),
                    mediaInfo.get(StreamType.Audio, i, "Format_Commercial_IfAny"),
                    mediaInfo.get(StreamType.Audio, i, "Format")
            );

            String features = mediaInfo.get(StreamType.Audio, i, "Format_AdditionalFeatures");
            String channels = CollectionUtils.getChannels(mediaInfo.get(StreamType.Audio, i, "Channels"));

            String codec = determineAudioCodec(formatCommercial, formatProfile, features);

            audioTracks.add(new String[]{codec, channels, language, title});
        }
        return audioTracks;
    }

    private String determineAudioCodec(String formatCommercial, String formatProfile, String features) {
        if (formatCommercial == null) return "Unknown";

        if (formatCommercial.contains("DTS-HD") && "XLL X".equals(features)) return "DTS-X";
        if (formatCommercial.contains("DTS-") && features != null && features.contains("ES")) return "DTS-ES";
        if ("MPEG Audio".equals(formatCommercial) && formatProfile != null && formatProfile.contains("Layer 3")) return "MP3";
        if (formatCommercial.contains("AAC")) return "AAC";
        if ("Dolby Digital".equals(formatCommercial)) return "DD";
        if ("DTS-HD Master Audio".equals(formatCommercial)) return "DTS-HD.MA";
        if ("DTS-HD High Resolution Audio".equals(formatCommercial)) return "DTS-HR";
        if ("Dolby Digital Plus".equals(formatCommercial)) return "DD+";
        if ("Dolby Digital Plus with Dolby Atmos".equals(formatCommercial)) return "DD+.Atmos";
        if ("Dolby TrueHD with Dolby Atmos".equals(formatCommercial)) return "TrueHD.Atmos";
        if ("Dolby TrueHD".equals(formatCommercial)) return "TrueHD";
        if ("DTS-HD MA + IMAX Enhanced".equals(formatCommercial)) return "IMAX.Enhanced.DTS-HD.MA";

        return formatCommercial;
    }

    /**
     * Exports media info to CSV file.
     * Each row is one file, with all audio tracks summarized in a single column as JSON-like array.
     *
     * @param mediaFiles list of media files to process
     * @param csvOutputPath path to output CSV file
     */

 }

