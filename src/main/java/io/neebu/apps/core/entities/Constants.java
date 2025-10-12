package io.neebu.apps.core.entities;

import java.util.Set;
import java.util.regex.Pattern;

public class Constants {
    public static final String DELIMITER       = "[\\[\\](){} _,.-]";
    public static final String OTT_PLATFORMS = "AMZN,ATVP,BMS,DSNP,GPLAY,HS,HULU,JC,JSTAR,MA,MX,NF,SM,SONY,YTUBE,ZEE5,iT";
    public static final String WEB_DL_DEFAULT = "Hybrid";

    public static final Set<String> VIDEO_EXTENSIONS = Set.of(".mkv", ".mp4", ".avi");

    // hard stopwords are words which will always be cleaned
    public static final String[] HARD_STOPWORDS  = { "1080", "1080i", "1080p", "2160p", "2160i", "3d", "480i", "480p", "576i", "576p", "360p",
            "10bit", "12bit", "360i", "720", "720i", "720p", "8bit", "ac3", "ac3ld", "ac3d", "ac3md", "amzn", "aoe", "atmos", "avc", "bd5", "bdrip",
            "bdrip", "blueray", "bluray", "brrip", "cam", "cd1", "cd2", "cd3", "cd4", "cd5", "cd6", "cd7", "cd8", "cd9", "dd20", "dd51", "disc1", "disc2",
            "disc3", "disc4", "disc5", "disc6", "disc7", "disc8", "disc9", "divx", "divx5", "dl", "dsr", "dsrip", "dts", "dtv", "dubbed", "dvd", "dvd1",
            "dvd2", "dvd3", "dvd4", "dvd5", "dvd6", "dvd7", "dvd8", "dvd9", "dvdivx", "dvdrip", "dvdscr", "dvdscreener", "emule", "etm", "fs", "fps",
            "h264", "h265", "hd", "hddvd", "hdr", "hdr10", "hdr10+", "hdrip", "hdtv", "hdtvrip", "hevc", "hrhd", "hrhdtv", "ind", "ituneshd", "ld", "md",
            "microhd", "multisubs", "mp3", "netflixhd", "nfo", "nfofix", "ntg", "ntsc", "ogg", "ogm", "pal", "pdtv", "pso", "r3", "r5", "remastered",
            "repack", "rerip", "remux", "roor", "rs", "rsvcd", "screener", "sd", "subbed", "subs", "svcd", "tc", "telecine", "telesync", "ts", "truehd",
            "uhd", "uncut", "unrated", "vcf", "vhs", "vhsrip", "webdl", "webrip", "workprint", "ws", "x264", "x265", "xf", "xvid", "xvidvd", "4k" };

    // soft stopwords are well known words which _may_ occur before the year token and will be cleaned conditionally
    public static final String[] SOFT_STOPWORDS  = { "complete", "custom", "dc", "docu", "doku", "extended", "fragment", "internal", "limited",
            "local", "ma", "multi", "pal", "proper", "read", "retail", "se", "www", "xxx" };

    // clean before splitting (needs delimiter in front!)
    public static final String[] CLEANWORDS      = { "24\\.000", "23\\.976", "23\\.98", "24\\.00", "web\\-dl", "web\\-rip", "blue\\-ray",
            "blu\\-ray", "dvd\\-rip" };
    public static final Pattern  TMDB_ID_PATTERN = Pattern.compile("(tmdbid|tmdb)[ ._-]?(\\d+)", Pattern.CASE_INSENSITIVE);

    public static final String SELECT_MOVIES_SQL = "SELECT absolute_path FILE_PATH FROM collection WHERE collection_type = 'MOVIE'";
    public static final String SELECT_TV_SQL = "SELECT absolute_path FILE_PATH FROM collection WHERE collection_type = 'TV'";

    public static final String INSERT_MEDIA_SQL = "INSERT INTO collection ( COLLECTION_TYPE ,ABSOLUTE_PATH ,FILE_NAME ,FILE_EXTENSION ,NAME ,SOURCE_TYPE ,SOURCE ,GROUP_NAME ,TMDB_ID ,RELEASE_YEAR ,FILE_SIZE ,RELEASE_DATE ,TMDB_NAME ,TMDB_DESCRIPTION ,SEASON_NUMBER ,EPISODE_NUMBER ,EPISODE_NAME ,EPISODE_OVERVIEW ,RESOLUTION ,HDR_FORMAT ,VIDEO_CODEC ,AUDIO_CODEC ,AUDIO_CHANNELS )\n" +
            "VALUES ( ? ,? ,? ,? ,? ,? ,? ,? ,? ,? ,? ,? ,? ,? ,? ,? ,? ,? ,? ,? ,? ,? ,? )";
    public static final String DELETE_MEDIA_SQL = "DELETE FROM collection WHERE ABSOLUTE_PATH = ?";


    public enum CollectionType {
        MOVIE,
        TV
    }
}
