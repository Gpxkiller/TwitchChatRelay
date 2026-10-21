package fr.gpxkiller.twitch;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
/** Bounded reads; no linked files, no group/world-readable credentials on POSIX. */
final class PrivateFiles {
 private PrivateFiles(){}
 static JsonElement read(Path path)throws IOException{
  for(Path p=path.toAbsolutePath();p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Linked private path refused");
  if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Regular private file required");
  if(Files.getFileStore(path).supportsFileAttributeView("posix"))for(var permission:Files.getPosixFilePermissions(path))if(permission.name().startsWith("GROUP_")||permission.name().startsWith("OTHERS_"))throw new IOException("Private file permissions required");
  try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){
   byte[] data=in.readNBytes(65537);if(data.length>65536)throw new IOException("Private file too large");
   try{return JsonParser.parseString(new String(data,StandardCharsets.UTF_8));}catch(RuntimeException invalid){throw new IOException("Invalid private data");}
  }
 }
}
