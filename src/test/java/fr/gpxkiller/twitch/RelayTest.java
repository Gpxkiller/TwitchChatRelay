package fr.gpxkiller.twitch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RelayTest {
    @TempDir Path directory;
    @Test void noDefaultAudienceOrAccounts(){assertTrue(RelayRules.initial("survival").isEmpty());}
    @Test void otherModesHaveNoDefaultAudience(){for(String mode:List.of("lobby","arena","public","private","friends","easy","adventure"))assertTrue(RelayRules.initial(mode).isEmpty());}
    @Test void uuidNotDisguiseNameDecidesAudience(){UUID first=UUID.randomUUID(),second=UUID.randomUUID();var rule=new RelayRules.Rule(false,Map.of(first,"SameName"));assertTrue(rule.allows(first));assertFalse(rule.allows(second));}
    @Test void rulesAreImmutable(){var targets=new HashMap<UUID,String>();UUID id=UUID.randomUUID();targets.put(id,"A");var rule=new RelayRules.Rule(false,targets);targets.clear();assertTrue(rule.allows(id));assertThrows(UnsupportedOperationException.class,()->rule.players().clear());}
    @Test void publicRuleCannotAlsoHavePrivateList(){assertThrows(IllegalArgumentException.class,()->new RelayRules.Rule(true,Map.of(UUID.randomUUID(),"A")));}
    @Test void allAudienceMatchesAnyUuid(){assertTrue(new RelayRules.Rule(true,Map.of()).allows(UUID.randomUUID()));}
    @Test void invalidChannelsRejected(){for(String raw:List.of("x/y","examplechannel?key=x","localhost:80","https://evil.example","@a","a\n"))assertThrows(IllegalArgumentException.class,()->RelayRules.channel(raw));}
    @Test void knownChannelUrlAndCaseNormalize(){assertEquals("examplechannel",RelayRules.channel("https://www.twitch.tv/ExampleChannel"));}
    @Test void controlsAndFormattingCannotBecomeChatActions(){assertEquals("aared/say @a",RelayRules.plain("a§ared\n/say @a\u202e",100));}
    @Test void truncatesByCodePointNotSurrogate(){assertEquals("😀😀",RelayRules.plain("😀😀😀",2));}
    @Test void firstPollNeverReplaysHistory(){var buffer=new RelayBuffer();buffer.offer("1","examplechannel","A","hello",1000);assertTrue(buffer.poll(-1,Set.of("examplechannel"),1001).messages().isEmpty());assertEquals(1,buffer.poll(-1,Set.of(),1001).cursor());}
    @Test void onlyRequestedChannelsAndNewMessages(){var buffer=new RelayBuffer();buffer.offer("1","examplechannel","A","a",1000);buffer.offer("2","other","B","b",1000);var batch=buffer.poll(0,Set.of("examplechannel"),1001);assertEquals(1,batch.messages().size());assertEquals("examplechannel",batch.messages().getFirst().channel());assertEquals(2,batch.cursor());}
    @Test void notificationDeduplicated(){var buffer=new RelayBuffer();buffer.offer("1","examplechannel","A","hello",1000);buffer.offer("1","examplechannel","A","hello",1000);assertEquals(1,buffer.poll(0,Set.of("examplechannel"),1001).messages().size());}
    @Test void queueIsBoundedAndOldDataExpires(){var buffer=new RelayBuffer();for(int i=0;i<3000;i++)buffer.offer(""+i,"examplechannel","A","x",1000);assertEquals(50,buffer.poll(0,Set.of("examplechannel"),1001).messages().size());assertTrue(buffer.poll(0,Set.of("examplechannel"),122000).messages().isEmpty());}
    @Test void paginationDoesNotSkipUnreadMatchingRows(){var buffer=new RelayBuffer();for(int i=0;i<60;i++)buffer.offer(""+i,"examplechannel","A","x",1000);var first=buffer.poll(0,Set.of("examplechannel"),1001);assertEquals(50,first.cursor());assertEquals(10,buffer.poll(first.cursor(),Set.of("examplechannel"),1001).messages().size());}
    @Test void bridgeRestartResetsFutureCursorWithoutHistory(){var buffer=new RelayBuffer();buffer.offer("a","examplechannel","A","x",1000);assertTrue(buffer.poll(100,Set.of("examplechannel"),1001).messages().isEmpty());}
    @Test void reconnectCannotTargetAnotherHostOrPlaintext(){for(String raw:List.of("ws://eventsub.wss.twitch.tv/ws","wss://evil.example/ws","wss://eventsub.wss.twitch.tv:444/ws","wss://user@eventsub.wss.twitch.tv/ws"))assertThrows(IllegalArgumentException.class,()->TwitchBridge.reconnectUri(raw));assertEquals("eventsub.wss.twitch.tv",TwitchBridge.reconnectUri("wss://eventsub.wss.twitch.tv/ws?x=1").getHost());}
    @Test void backendKeyMapsOnlyToItsOwnMode(){String first="a".repeat(64),second="b".repeat(64);var keys=Map.of("survival",first,"lobby",second);assertEquals("survival",TwitchBridge.authenticate(keys,first));assertNull(TwitchBridge.authenticate(keys,null));assertNull(TwitchBridge.authenticate(keys,"c".repeat(64)));}
    @Test void realHttpBridgeRejectsUnauthorizedAndOversized()throws Exception{
        try(var bridge=new TwitchBridge(directory,Map.of("survival","a".repeat(64)),0);var http=HttpClient.newHttpClient()){
            bridge.start();URI uri=URI.create("http://127.0.0.1:"+bridge.port()+"/v1/poll");
            assertEquals(403,http.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(413,http.send(HttpRequest.newBuilder(uri).header("X-Twitch-Relay","a".repeat(64)).POST(HttpRequest.BodyPublishers.ofString("x".repeat(8193))).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @Test void realHttpPollHasNoCredentialsAndCannotSelectAnotherMode()throws Exception{
        try(var bridge=new TwitchBridge(directory,Map.of("survival","a".repeat(64)),0);var http=HttpClient.newHttpClient()){
            bridge.start();URI uri=URI.create("http://127.0.0.1:"+bridge.port()+"/v1/poll");
            var response=http.send(HttpRequest.newBuilder(uri).header("X-Twitch-Relay","a".repeat(64)).POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"lobby\",\"channels\":[],\"after\":-1,\"epoch\":\"\"}")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());assertTrue(response.body().contains("survival"));assertFalse(response.body().contains("access_token"));assertFalse(response.body().contains("refresh_token"));assertFalse(response.body().contains("aaaaaa"));
        }
    }
}
