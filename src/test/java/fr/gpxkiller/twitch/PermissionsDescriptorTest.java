package fr.gpxkiller.twitch;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class PermissionsDescriptorTest {
 @Test void consoleCanReachExecutorWhilePlayersNeedExplicitPermission()throws Exception{
  try(var in=getClass().getResourceAsStream("/plugin.yml")){
   assertNotNull(in);String yaml=new String(in.readAllBytes(),StandardCharsets.UTF_8);
   assertTrue(yaml.contains("default: false"));assertFalse(yaml.contains("    permission:"));
  }
 }
}
