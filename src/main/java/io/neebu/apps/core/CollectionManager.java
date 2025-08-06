package io.neebu.apps.core;

import lombok.Getter;
import lombok.ToString;

import java.nio.file.Path;
import java.nio.file.Paths;

@Deprecated
@Getter
@ToString
public class CollectionManager {
    private String fileName;
    private Boolean existInDatabase;
    private Boolean existInPath;
    private Boolean renameRequired = false;
    private String addOrDeleteOrSkip;
    private String newFileName;

    public CollectionManager(String fileName, Boolean existInDatabase, Boolean existInPath){
        this.fileName = fileName;
        this.existInDatabase = existInDatabase;
        this.existInPath = existInPath;

        if(existInDatabase && !existInPath){
            this.addOrDeleteOrSkip = "DELETE";
        }
        if(!existInDatabase && existInPath){
            this.addOrDeleteOrSkip = "ADD";
        }
        if(existInDatabase && existInPath){
            this.addOrDeleteOrSkip = "SKIP";
        }
    }

    public void setNewFileName(String newFileName){
        Path source = Paths.get(this.fileName);
        this.newFileName = source.resolveSibling(newFileName).toAbsolutePath().toString();
        if(!this.fileName.equals(this.newFileName)){
            this.renameRequired=true;
        }
    }
}
