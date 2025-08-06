package io.neebu.apps.conn;

import io.neebu.apps.core.entities.Constants;
import io.neebu.apps.core.models.LibraryMovie;
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

    @Deprecated
    @SneakyThrows
    public List<String> getMovies(){
        List<String> moviesDb = new ArrayList<>();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(Constants.SELECT_SQL);
        while (rs.next()) {
            moviesDb.add(rs.getString("FILE_PATH"));
        }
        rs.close();
        stmt.close();
        return moviesDb;
    }


    @Deprecated
    @SneakyThrows
    public void insert(LibraryMovie libraryMovie){
        PreparedStatement statement = conn.prepareStatement(Constants.INSERT_SQL);
        statement.setString(1, libraryMovie.getFilePath().toString());
        statement.setString(2, libraryMovie.getBaseName());
        statement.setString(3, libraryMovie.getFileExtension());
        statement.setString(4, libraryMovie.getMovieName());
        statement.setInt(5, libraryMovie.getMovieYear());
        statement.setString(6, libraryMovie.getSourceType());
        statement.setString(7, libraryMovie.getSource());
        statement.setInt(8, libraryMovie.getTmdbMovie().getTmdbId());
        statement.setString(9, libraryMovie.getGroupName());
        statement.setLong(10, libraryMovie.getFileSize());
        statement.setString(11, libraryMovie.getResolution());
        statement.setString(12, libraryMovie.getHdrFormat());
        statement.setString(13, libraryMovie.getVideoCodec());
        statement.setString(14, libraryMovie.getAudioCodec());
        statement.setString(15, libraryMovie.getAudioChannels());
        statement.setString(16, libraryMovie.getRenamedFormat());
        statement.setString(17, libraryMovie.getTmdbMovie().getTmdbName());
        statement.setDate(18, Date.valueOf(libraryMovie.getTmdbMovie().getReleaseDate()));
        statement.executeUpdate();
        conn.commit();
        LOGGER.info("Inserted Record : {}",libraryMovie.getFilePath().toString());
    }

    @Deprecated
    @SneakyThrows
    public void delete(String filePath){
        PreparedStatement statement = conn.prepareStatement(Constants.DELETE_SQL);
        statement.setString(1,filePath);
        statement.executeUpdate();
        conn.commit();
        LOGGER.info("Deleted Record : {}",filePath);
    }
}
