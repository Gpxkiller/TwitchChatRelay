package fr.gpxkiller.twitch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import static org.junit.jupiter.api.Assertions.*;
class PrivateFilesTest {
 @TempDir Path root;
 private Path file(String content)throws Exception{Path p=Files.createTempFile(root,"private-",".json");Files.writeString(p,content);return p;}
 @Test void acceptsBoundedPrivateJson()throws Exception{assertEquals("fixture",PrivateFiles.read(file("\"fixture\"")).getAsString());}
 @Test void rejectsOversizeAndMalformedData()throws Exception{assertThrows(java.io.IOException.class,()->PrivateFiles.read(file("x".repeat(65537))));assertThrows(java.io.IOException.class,()->PrivateFiles.read(file("{")));}
 @Test void rejectsDirectory()throws Exception{assertThrows(java.io.IOException.class,()->PrivateFiles.read(root));}
 @Test void rejectsBroadPermissionsOnPosix()throws Exception{org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));Path p=file("{}");Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-r--r--"));assertThrows(java.io.IOException.class,()->PrivateFiles.read(p));}
}
