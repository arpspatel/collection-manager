package io.neebu.apps.conn;

import io.neebu.apps.core.entities.Constants;
import io.neebu.apps.core.models.MediaFile;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DatabaseApp {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseApp.class.getName());

    private Connection conn;

    @SneakyThrows
    public void connect(String url, String user, String pass){
        conn = DriverManager.getConnection(url,user,pass);
    }

    @SneakyThrows
    public void close(){
        conn.close();
    }

    @SneakyThrows
    public List<String> getCollection(String collectionSql){
        List<String> moviesDb = new ArrayList<>();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(collectionSql);
        while (rs.next()) {
            moviesDb.add(rs.getString("FILE_PATH"));
        }
        rs.close();
        stmt.close();
        return moviesDb;
    }

    @SneakyThrows
    public void insert(MediaFile mediaFile){
        PreparedStatement statement = conn.prepareStatement(Constants.INSERT_MEDIA_SQL);
        statement.setString(1,mediaFile.getCollectionType().toString());
        statement.setString(2,mediaFile.getAbsolutePath().toString());
        statement.setString(3,mediaFile.getBaseName());
        statement.setString(4,mediaFile.getFileExtension());
        statement.setString(5,mediaFile.getName());
        statement.setString(6,mediaFile.getSourceType());
        statement.setString(7,mediaFile.getSource());
        statement.setString(8,mediaFile.getGroupName());
        statement.setString(9,mediaFile.getTmdbId().toString());
        if(mediaFile.getReleaseYear()==null) { statement.setNull(10, Types.INTEGER); } else {statement.setInt(10, mediaFile.getReleaseYear()); }
        statement.setLong(11,mediaFile.getFileSize());
        statement.setString(12,mediaFile.getReleaseDate());
        statement.setString(13,mediaFile.getTmdbName());
        statement.setString(14,mediaFile.getTmdbDescription());
        statement.setString(15,mediaFile.getSeasonNumber());
        statement.setString(16,mediaFile.getEpisodeNumber());
        statement.setString(17,mediaFile.getEpisodeName());
        statement.setString(18,mediaFile.getEpisodeOverview());
        statement.setString(19,mediaFile.getResolution());
        statement.setString(20,mediaFile.getHdrFormat());
        statement.setString(21,mediaFile.getVideoCodec());
        statement.setString(22,mediaFile.getAudioCodec());
        statement.setString(23,mediaFile.getAudioChannels());
        statement.executeUpdate();
        conn.commit();
        LOGGER.info("Inserted Record : {}",mediaFile.getAbsolutePath().toString());
    }

    @SneakyThrows
    public void delete(String filePath){
        PreparedStatement statement = conn.prepareStatement(Constants.DELETE_MEDIA_SQL);
        statement.setString(1,filePath);
        statement.executeUpdate();
        conn.commit();
        LOGGER.info("Deleted Record : {}",filePath);
    }

}
