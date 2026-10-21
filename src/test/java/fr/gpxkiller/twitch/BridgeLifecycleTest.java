package fr.gpxkiller.twitch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.http.WebSocket;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class BridgeLifecycleTest {
    @TempDir Path directory;
    private static Field field(String name)throws Exception {var field=TwitchBridge.class.getDeclaredField(name);field.setAccessible(true);return field;}
    @SuppressWarnings("unchecked")
    private static Map<String,String> map(TwitchBridge bridge,String name)throws Exception{return (Map<String,String>)field(name).get(bridge);}
    private Object connection(TwitchBridge bridge)throws Exception{
        Class<?> type=Class.forName("fr.gpxkiller.twitch.TwitchBridge$Connection");var constructor=type.getDeclaredConstructor(TwitchBridge.class,boolean.class);constructor.setAccessible(true);Object connection=constructor.newInstance(bridge,false);
        var socket=type.getDeclaredField("socket");socket.setAccessible(true);socket.set(connection,Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WebSocket.class},(proxy,method,args)->method.getReturnType()==void.class?null:CompletableFuture.completedFuture(null)));
        return connection;
    }
    private void receive(TwitchBridge bridge,Object connection,String json)throws Exception{
        var method=TwitchBridge.class.getDeclaredMethod("receive",connection.getClass(),JsonObject.class);method.setAccessible(true);method.invoke(bridge,connection,JsonParser.parseString(json).getAsJsonObject());
    }
    @Test void idleHealthIsReplacedRatherThanLeavingConnectedStatus()throws Exception{
        try(var bridge=new TwitchBridge(directory,Map.of(),0)){
            Files.writeString(directory.resolve("relay-health.json"),"{\"websocket\":true}");
            var method=TwitchBridge.class.getDeclaredMethod("reconcile");method.setAccessible(true);method.invoke(bridge);
            var health=JsonParser.parseString(Files.readString(directory.resolve("relay-health.json"))).getAsJsonObject();
            assertFalse(health.get("websocket").getAsBoolean());assertEquals("Aucune chaîne activée",health.get("status").getAsString());
        }
    }
    @Test void revocationClearsBroadcasterAndStaleConnectionsAreIgnored()throws Exception{
        try(var bridge=new TwitchBridge(directory,Map.of(),0)){
            var active=connection(bridge);var stale=connection(bridge);field("active").set(bridge,active);
            map(bridge,"subscriptions").put("examplechannel","current");map(bridge,"broadcasters").put("examplechannel","123");
            String event="{\"metadata\":{\"message_type\":\"revocation\"},\"payload\":{\"subscription\":{\"id\":\"current\"}}}";
            receive(bridge,stale,event);assertEquals("current",map(bridge,"subscriptions").get("examplechannel"));
            receive(bridge,active,event);assertTrue(map(bridge,"subscriptions").isEmpty());assertTrue(map(bridge,"broadcasters").isEmpty());
        }
    }
    @Test void reconnectWelcomeMayHaveNullKeepalive() {
        var session=JsonParser.parseString("{\"keepalive_timeout_seconds\":null}").getAsJsonObject();
        assertEquals(35_000,TwitchBridge.sessionTimeout(session,35_000));
        assertEquals(20_000,TwitchBridge.sessionTimeout(new JsonObject(),20_000));
    }
    @Test void normalWelcomeBoundsKeepalive() {
        var session=new JsonObject();session.addProperty("keepalive_timeout_seconds",20);
        assertEquals(20_000,TwitchBridge.sessionTimeout(session,35_000));
        for(int invalid:new int[]{-1,0,9,601}){session.addProperty("keepalive_timeout_seconds",invalid);assertThrows(IllegalArgumentException.class,()->TwitchBridge.sessionTimeout(session,20_000));}
    }
    @Test void revokedUnknownOrReplacedSubscriptionCannotDeliver() {
        var subscription=JsonParser.parseString("{\"id\":\"current\",\"status\":\"enabled\",\"type\":\"channel.chat.message\",\"version\":\"1\"}").getAsJsonObject();
        assertTrue(TwitchBridge.subscriptionMatches("current",subscription));
        assertFalse(TwitchBridge.subscriptionMatches(null,subscription));
        assertFalse(TwitchBridge.subscriptionMatches("replacement",subscription));
        subscription.addProperty("status","authorization_revoked");
        assertFalse(TwitchBridge.subscriptionMatches("current",subscription));
    }
}
