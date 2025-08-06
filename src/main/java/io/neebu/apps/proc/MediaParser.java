package io.neebu.apps.proc;

import com.amilesend.mediainfo.MediaInfo;
import com.amilesend.mediainfo.lib.MediaInfoAccessor;
import com.amilesend.mediainfo.lib.MediaInfoLibrary;
import com.amilesend.mediainfo.type.StreamType;
import io.neebu.apps.core.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

public class MediaParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(MediaParser.class.getName());

    private MediaInfo myVideo;
    private MediaInfoLibrary library = MediaInfoLibrary.newInstance();
    private MediaInfoAccessor accessor = new MediaInfoAccessor(library);

    public MediaParser(Path filePath) throws IOException {
        this.myVideo = new MediaInfo(accessor).open(filePath.toFile());
    }


    public String getVideoCodec() {
        String videoCodec = StringUtils.isBlank(myVideo.get(StreamType.Video, 0, "CodecID/Hint")) ?
                myVideo.get(StreamType.Video, 0, "Format") : myVideo.get(StreamType.Video, 0, "CodecID/Hint");

        // fix for Microsoft VC-1
        if (StringUtils.containsIgnoreCase(videoCodec, "Microsoft")) {
            videoCodec = myVideo.get(StreamType.Video, 0, "Format");
        }

        String codecId = myVideo.get(StreamType.General, 0, "CodecID");
        // workaround for XVID
        if (codecId.equalsIgnoreCase("XVID")) {
            // XVID is open source variant MP4, only detectable through codecId
            videoCodec = "XVID";
        }
        if (videoCodec.equalsIgnoreCase("AVC")) {
            // XVID is open source variant MP4, only detectable through codecId
            videoCodec = "H264";
        }

        // detect the right MPEG version
        if (StringUtils.containsIgnoreCase(videoCodec, "MPEG")) {
            // search for the version
            try {
                // Version 2
                int version = Integer.parseInt(myVideo.get(StreamType.Video, 0, "Format_Version").replaceAll("\\D*", ""));
                videoCodec = "MPEG" + version;
            } catch (Exception e) {
                LOGGER.trace("could not parse MPEG version:" + e.getMessage());
            }
        }
        return videoCodec;
    }

    public String getVideoFormat() {
        int w = Integer.parseInt(myVideo.get(StreamType.Video, 0, "Width"));
        int h = Integer.parseInt(myVideo.get(StreamType.Video, 0, "Height"));
        // use XBMC implementation https://github.com/xbmc/xbmc/blob/master/xbmc/utils/StreamDetails.cpp#L559
        if (w == 0 || h == 0) {
            return "";
        }
        // https://en.wikipedia.org/wiki/Low-definition_television
        else if (w <= CollectionUtils.blur(128) && h <= CollectionUtils.blur(96)) { // MMS-Small 96p 128×96 4:3
            return "96p";
        } else if (w <= CollectionUtils.blur(160) && h <= CollectionUtils.blur(120)) { // QQVGA 120p 160×120 4:3
            return "120p";
        } else if (w <= CollectionUtils.blur(176) && h <= CollectionUtils.blur(144)) { // QCIF Webcam 144p 176×144 11:9
            return "144p";
        } else if (w <= CollectionUtils.blur(256) && h <= CollectionUtils.blur(144)) { // YouTube 144p 144p 256×144 16:9
            return "144p";
        } else if (w <= CollectionUtils.blur(320) && h <= CollectionUtils.blur(240)) { // NTSC square pixel 240p 320×240 4:3
            return "240p";
        } else if (w <= CollectionUtils.blur(352) && h <= CollectionUtils.blur(240)) { // SIF (525) 240p 352×240 4:3
            return "240p";
        } else if (w <= CollectionUtils.blur(426) && h <= CollectionUtils.blur(240)) { // NTSC widescreen 240p 426×240 16:9
            return "240p";
        } else if (w <= CollectionUtils.blur(480) && h <= CollectionUtils.blur(272)) { // PSP 288p 480×272 30:17
            return "288p";
        } else if (w <= CollectionUtils.blur(480) && h <= CollectionUtils.blur(360)) { // 360p 360p 480×360 4:3
            return "360p";
        } else if (w <= CollectionUtils.blur(640) && h <= CollectionUtils.blur(360)) { // Wide 360p 360p 640×360 16:9
            return "360p";
        }
        // https://en.wikipedia.org/wiki/480p
        else if (w <= CollectionUtils.blur(640) && h <= CollectionUtils.blur(480)) { // 480p 640×480 4:3
            return "480p";
        } else if (w <= CollectionUtils.blur(720) && h <= CollectionUtils.blur(480)) { // Rec. 601 720×480 3:2
            return "480p";
        } else if (w <= CollectionUtils.blur(800) && h <= CollectionUtils.blur(480)) { // Rec. 601 plus a quarter 800×480 5:3
            return "480p";
        } else if (w <= CollectionUtils.blur(853) && h <= CollectionUtils.blur(480)) { // Wide 480p 853.33×480 16:9 (unscaled)
            return "480p";
        } else if (w <= CollectionUtils.blur(776) && h <= CollectionUtils.blur(592)) {
            // 720x576 (PAL) (handbrake sometimes encode it to a max of 776 x 592)
            return "576p";
        } else if (w <= CollectionUtils.blur(1024) && h <= CollectionUtils.blur(576)) { // Wide 576p 1024×576 16:9
            return "576p";
        } else if (w <= CollectionUtils.blur(960) && h <= CollectionUtils.blur(544)) {
            // 960x540 (sometimes 544 which is multiple of 16)
            return "540p";
        } else if (h <= CollectionUtils.blur(720)) { // 720p Widescreen 16:9
            return "720p";
        } else if (h <= CollectionUtils.blur(1080)) { // 1080p HD Widescreen 16:9
            return "1080p";
        } else if (h <= CollectionUtils.blur(1440)) { // 1440p HD Widescreen 4:3
            return "1440p";
        } else if (w <= CollectionUtils.blur(3840) && h <= CollectionUtils.blur(2160)) { // 4K Ultra-high-definition television
            return "2160p";
        } else if (w <= CollectionUtils.blur(3840) && h <= CollectionUtils.blur(1600)) { // 4K Ultra-wide-television
            return "2160p";
        } else if (w <= CollectionUtils.blur(4096) && h <= CollectionUtils.blur(2160)) { // DCI 4K (native resolution)
            return "2160p";
        } else if (w <= CollectionUtils.blur(4096) && h <= CollectionUtils.blur(1716)) { // DCI 4K (CinemaScope cropped)
            return "2160p";
        } else if (w <= CollectionUtils.blur(3996) && h <= CollectionUtils.blur(2160)) { // DCI 4K (flat cropped)
            return "2160p";
        }

        return "4320p";
    }

    public String getHdrFormat() {
        // detect them combined!
        String hdrFormat = CollectionUtils.detectHdrFormat(myVideo.get(StreamType.Video, 0, "HDR_Format") + " / " + myVideo.get(StreamType.Video, 0, "HDR_Format_String") + " / " + myVideo.get(StreamType.Video, 0, "HDR_Format_Compatibility"));

        if (StringUtils.isBlank(hdrFormat)) {
            // no HDR format found? try another mediainfo field
            hdrFormat = CollectionUtils.detectHdrFormat(myVideo.get(StreamType.Video, 0, "transfer_characteristics"));
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

    public String getAudioCodec() {
        String[] audioInfo = CollectionUtils.parseName(myVideo);
        if (audioInfo == null) {
            return null;//audioInfo[0];
            //if(!this.audioCodec.equalsIgnoreCase("MP3")) {this.audioChannels = audioInfo[1];}
        }
        return audioInfo[0];
    }

    public String getAudioChannels() {
        String[] audioInfo = CollectionUtils.parseName(myVideo);
        if (audioInfo == null || audioInfo[0].equalsIgnoreCase("MP3")) {
            return null;
        }
        return audioInfo[1];
    }


}
