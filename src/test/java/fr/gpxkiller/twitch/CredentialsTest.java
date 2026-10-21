package fr.gpxkiller.twitch;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CredentialsTest {
 @TempDir Path root;
 private void write(String name,JsonObject data)throws Exception{
  Path temp=Files.createTempFile(root,".private-",".json");Files.writeString(temp,data.toString());Files.move(temp,root.resolve(name),StandardCopyOption.REPLACE_EXISTING);
 }
 private JsonObject token(){double now=System.currentTimeMillis()/1000d;var t=JsonParser.parseString("{\"access_token\":\"fixture-access\",\"user_id\":\"123\",\"login\":\"exampleviewer\",\"client_id\":\"exampleclient\",\"scopes\":[\"user:read:chat\"]}").getAsJsonObject();t.addProperty("expires_at",now+3600);t.addProperty("validated_at",now);return t;}
 private void check(JsonObject token,boolean valid)throws Exception{
  write("bridge.json",JsonParser.parseString("{\"enabled\":true,\"login\":\"exampleviewer\",\"clientId\":\"exampleclient\"}").getAsJsonObject());write("tokens.json",token);
  try(var bridge=new TwitchBridge(root,Map.of(),0)){var m=TwitchBridge.class.getDeclaredMethod("credentials");m.setAccessible(true);if(valid)assertNotNull(m.invoke(bridge));else assertThrows(InvocationTargetException.class,()->m.invoke(bridge));}
 }
 @Test void expectedReadOnlyIdentityAccepted()throws Exception{check(token(),true);}
 @Test void otherIdentityRefused()throws Exception{var t=token();t.addProperty("login","other");check(t,false);}
 @Test void otherApplicationRefused()throws Exception{var t=token();t.addProperty("client_id","other");check(t,false);}
 @Test void expiredAndStaleValidationRefused()throws Exception{var t=token();t.addProperty("expires_at",0);check(t,false);t=token();t.addProperty("validated_at",0);check(t,false);}
 @Test void futureValidationAndExtraScopeRefused()throws Exception{var t=token();t.addProperty("validated_at",System.currentTimeMillis()/1000d+120);check(t,false);t=token();t.getAsJsonArray("scopes").add("chat:edit");check(t,false);}
}
