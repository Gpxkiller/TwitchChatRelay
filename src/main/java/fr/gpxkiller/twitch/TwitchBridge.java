package fr.gpxkiller.twitch;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Standalone VPS process; only this account can read Twitch OAuth credentials. */
public final class TwitchBridge implements AutoCloseable {
    private record Lease(Set<String> channels,long until) { }
    private record Credentials(String token,String user) { }
    private final Path directory;
    private final Map<String,String> keys;
    private final Map<String,Lease> leases=new HashMap<>();
    private final RelayBuffer buffer=new RelayBuffer();
    private final AtomicInteger queuedFrames=new AtomicInteger();
    private final String epoch=UUID.randomUUID().toString();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ScheduledExecutorService manager=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"twitch-chat-relay-eventsub"));
    private final ExecutorService requests=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),new ThreadPoolExecutor.AbortPolicy());
    private final Map<String,String> subscriptions=new HashMap<>(),broadcasters=new HashMap<>();
    private final Map<String,Long> cooldown=new HashMap<>();
    private final HttpServer server;
    private volatile Connection active,pending;
    private volatile String status="Connexion Twitch en cours";
    private volatile boolean closed;
    private long retryAt;
    private int failures;
    // IPv4 loopback is deliberate: the bridge must never bind publicly or via DNS.
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    TwitchBridge(Path directory,Map<String,String> keys,int port)throws IOException{
        this.directory=directory;this.keys=Map.copyOf(keys);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),16);server.setExecutor(requests);server.createContext("/v1/poll",this::poll);
    }
    private Credentials credentials()throws IOException{
        JsonObject data=PrivateFiles.read(directory.resolve("tokens.json")).getAsJsonObject();
        JsonObject config=PrivateFiles.read(directory.resolve("bridge.json")).getAsJsonObject();
        double now=System.currentTimeMillis()/1000d,expires=data.get("expires_at").getAsDouble(),validated=data.get("validated_at").getAsDouble();
        if(!config.get("enabled").getAsBoolean()||!config.get("login").getAsString().equals(data.get("login").getAsString())||!config.get("clientId").getAsString().equals(data.get("client_id").getAsString())||!data.get("user_id").getAsString().matches("[0-9]+")||!Double.isFinite(expires)||expires<=now||!Double.isFinite(validated)||validated<now-3600||validated>now+60)throw new IOException("Autorisation indisponible");
        JsonArray scopes=data.getAsJsonArray("scopes");if(scopes.size()!=1||!scopes.get(0).getAsString().equals("user:read:chat"))throw new IOException("Droits refusés");
        return new Credentials(data.get("access_token").getAsString(),data.get("user_id").getAsString());
    }
    private JsonObject api(String method,String path,JsonObject body)throws IOException,InterruptedException{
        Credentials auth=credentials();
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create("https://api.twitch.tv/helix/"+path)).timeout(Duration.ofSeconds(6)).header("Client-Id",PrivateFiles.read(directory.resolve("bridge.json")).getAsJsonObject().get("clientId").getAsString()).header("Authorization","Bearer "+auth.token());
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body.toString()));
        if(body!=null)request.header("Content-Type","application/json");
        HttpResponse<InputStream> response=http.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
        try(InputStream stream=response.body()){
            byte[] bytes=stream.readNBytes(262145);
            if(bytes.length>262144||response.statusCode()<200||response.statusCode()>=300)throw new IOException("Twitch API refusée HTTP"+response.statusCode());
            return bytes.length==0?new JsonObject():JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    private synchronized Set<String> desired(){
        long now=System.currentTimeMillis();leases.entrySet().removeIf(e->e.getValue().until()<now);
        Set<String> channels=new TreeSet<>();leases.values().forEach(l->channels.addAll(l.channels()));return channels;
    }
    static String authenticate(Map<String,String> keys,String supplied){
        if(supplied==null||!supplied.matches("[a-f0-9]{64}"))return null;
        String match=null;for(var entry:keys.entrySet())if(MessageDigest.isEqual(entry.getValue().getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.US_ASCII)))match=entry.getKey();return match;
    }
    private void poll(HttpExchange exchange)throws IOException{
        try(exchange){
            String mode=authenticate(keys,exchange.getRequestHeaders().getFirst("X-Twitch-Relay"));
            if(mode==null){reply(exchange,403,new JsonObject());return;}
            if(!exchange.getRequestURI().getRawPath().equals("/v1/poll")||exchange.getRequestURI().getRawQuery()!=null||!exchange.getRequestMethod().equals("POST")){reply(exchange,404,new JsonObject());return;}
            byte[] bytes=exchange.getRequestBody().readNBytes(8193);if(bytes.length>8192){reply(exchange,413,new JsonObject());return;}
            try{
                JsonObject input=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();Set<String> channels=new TreeSet<>();JsonArray array=input.getAsJsonArray("channels");if(array.size()>8)throw new IllegalArgumentException("Trop de chaînes");for(JsonElement raw:array)channels.add(RelayRules.channel(raw.getAsString()));
                long after=input.get("after").getAsLong();String oldEpoch=input.get("epoch").getAsString();
                synchronized(this){Set<String> union=desired();Lease previous=leases.remove(mode);union=desired();union.addAll(channels);if(union.size()>8){if(previous!=null)leases.put(mode,previous);reply(exchange,409,new JsonObject());return;}leases.put(mode,new Lease(Set.copyOf(channels),System.currentTimeMillis()+30_000));}
                RelayBuffer.Batch batch=buffer.poll(epoch.equals(oldEpoch)?after:-1,channels,System.currentTimeMillis());
                JsonObject output=new JsonObject();output.addProperty("mode",mode);output.addProperty("epoch",epoch);output.addProperty("cursor",batch.cursor());output.addProperty("status",status);JsonArray messages=new JsonArray();
                for(var row:batch.messages()){JsonObject event=new JsonObject();event.addProperty("channel",row.channel());event.addProperty("author",row.author());event.addProperty("text",row.text());messages.add(event);}output.add("messages",messages);reply(exchange,200,output);
            }catch(RuntimeException invalid){reply(exchange,400,new JsonObject());}
        }
    }
    private static void reply(HttpExchange exchange,int code,JsonObject body)throws IOException{
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);
    }
    static URI reconnectUri(String value){
        URI uri=URI.create(value);
        if(!uri.getScheme().equals("wss")||!"eventsub.wss.twitch.tv".equals(uri.getHost())||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443)||uri.getFragment()!=null)throw new IllegalArgumentException("Reconnexion non autorisée");return uri;
    }
    private void connect(URI uri,boolean handover)throws IOException,InterruptedException,ExecutionException,TimeoutException{
        credentials();Connection connection=new Connection(handover);pending=connection;
        try{connection.socket=http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(8)).buildAsync(uri,connection).get(10,TimeUnit.SECONDS);}
        catch(InterruptedException|ExecutionException|TimeoutException|RuntimeException failure){pending=null;throw failure;}
    }
    private void welcome(Connection connection,JsonObject session){
        if(closed)return;
        if(connection!=pending)return;
        Connection old=active;connection.id=session.get("id").getAsString();connection.timeout=sessionTimeout(session,old==null?20_000:old.timeout);connection.last=System.currentTimeMillis();active=connection;pending=null;
        if(!connection.handover){subscriptions.clear();broadcasters.clear();cooldown.clear();}
        if(old!=null&&old!=connection)old.socket.sendClose(WebSocket.NORMAL_CLOSURE,"handover");
        failures=0;status="Connexion Twitch en cours";
        reconcile();
    }
    private void reconcile(){
        if(closed)return;
        try{
            Set<String> desired=desired();long now=System.currentTimeMillis();
            if(desired.isEmpty()){if(active!=null)active.socket.sendClose(WebSocket.NORMAL_CLOSURE,"idle");if(pending!=null&&pending.socket!=null)pending.socket.abort();pending=null;active=null;subscriptions.clear();broadcasters.clear();status="Aucune chaîne activée";return;}
            if(pending!=null&&now-pending.created>15_000){if(pending.socket!=null)pending.socket.abort();pending=null;retryAt=now+2000;status="Reconnexion Twitch en cours";}
            if(active!=null&&now-active.last>active.timeout+5000){active.socket.abort();active=null;subscriptions.clear();broadcasters.clear();}
            if(active==null){if(pending==null&&now>=retryAt)connect(URI.create("wss://eventsub.wss.twitch.tv/ws?keepalive_timeout_seconds=20"),false);return;}
            for(String channel:List.copyOf(subscriptions.keySet()))if(!desired.contains(channel)){api("DELETE","eventsub/subscriptions?id="+URLEncoder.encode(subscriptions.get(channel),StandardCharsets.UTF_8),null);subscriptions.remove(channel);broadcasters.remove(channel);}
            for(String channel:desired)if(!subscriptions.containsKey(channel)&&now>=cooldown.getOrDefault(channel,0L)){
                cooldown.put(channel,now+60_000);
                try{
                    JsonArray users=api("GET","users?login="+channel,null).getAsJsonArray("data");if(users.size()!=1)throw new IOException("Chaîne inconnue");String broadcaster=users.get(0).getAsJsonObject().get("id").getAsString();
                    JsonObject subscription=new JsonObject();subscription.addProperty("type","channel.chat.message");subscription.addProperty("version","1");JsonObject condition=new JsonObject();condition.addProperty("broadcaster_user_id",broadcaster);condition.addProperty("user_id",credentials().user());subscription.add("condition",condition);JsonObject transport=new JsonObject();transport.addProperty("method","websocket");transport.addProperty("session_id",active.id);subscription.add("transport",transport);
                    String id=api("POST","eventsub/subscriptions",subscription).getAsJsonArray("data").get(0).getAsJsonObject().get("id").getAsString();subscriptions.put(channel,id);broadcasters.put(channel,broadcaster);
                    writeHealth();
                }catch(IOException|RuntimeException failure){status="Chaîne indisponible ou autorisation à vérifier";}
            }
            if(subscriptions.keySet().containsAll(desired))status="Connecté à Twitch";
            writeHealth();
        }catch(InterruptedException stopping){Thread.currentThread().interrupt();}
        catch(IOException|ExecutionException|TimeoutException|RuntimeException failure){status="Reconnexion Twitch en cours";retryAt=System.currentTimeMillis()+Math.min(60_000,2000L<<Math.min(failures++,5));if(active==null&&pending!=null){if(pending.socket!=null)pending.socket.abort();pending=null;}}
        finally{writeHealth();}
    }
    static long sessionTimeout(JsonObject session,long previous){
        JsonElement value=session.get("keepalive_timeout_seconds");
        if(value==null||value.isJsonNull())return previous;
        int seconds=value.getAsInt();if(seconds<10||seconds>600)throw new IllegalArgumentException("Durée Twitch invalide");return seconds*1000L;
    }
    static boolean subscriptionMatches(String expected,JsonObject subscription){
        return expected!=null&&expected.equals(subscription.get("id").getAsString())&&"enabled".equals(subscription.get("status").getAsString())&&"channel.chat.message".equals(subscription.get("type").getAsString())&&"1".equals(subscription.get("version").getAsString());
    }
    private void writeHealth(){
        try{
            JsonObject state=new JsonObject();state.addProperty("status",status);state.addProperty("websocket",active!=null);state.addProperty("at",Instant.now().toString());JsonArray names=new JsonArray();new TreeSet<>(subscriptions.keySet()).forEach(names::add);state.add("channels",names);
            Path temporary=Files.createTempFile(directory,".relay-",".tmp");try{Files.writeString(temporary,state.toString());Files.move(temporary,directory.resolve("relay-health.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temporary);}
        }catch(IOException ignored){/* No chat content or credentials in diagnostics. */}
    }
    private void receive(Connection source,JsonObject envelope){
        if(source!=active&&source!=pending)return;
        JsonObject metadata=envelope.getAsJsonObject("metadata"),payload=envelope.getAsJsonObject("payload");String type=metadata.get("message_type").getAsString();source.last=System.currentTimeMillis();
        switch(type){
            case "session_welcome" -> welcome(source,payload.getAsJsonObject("session"));
            case "session_keepalive" -> { }
            case "session_reconnect" -> {try{if(source==active&&pending==null)connect(reconnectUri(payload.getAsJsonObject("session").get("reconnect_url").getAsString()),true);}catch(InterruptedException stopping){Thread.currentThread().interrupt();}catch(IOException|ExecutionException|TimeoutException|RuntimeException failure){status="Reconnexion Twitch en cours";}}
            case "revocation" -> {status="Autorisation Twitch révoquée";String id=payload.getAsJsonObject("subscription").get("id").getAsString();subscriptions.entrySet().removeIf(e->{if(!e.getValue().equals(id))return false;broadcasters.remove(e.getKey());return true;});writeHealth();}
            case "notification" -> {
                if(!metadata.get("subscription_type").getAsString().equals("channel.chat.message"))return;
                JsonObject event=payload.getAsJsonObject("event");String channel=RelayRules.channel(event.get("broadcaster_user_login").getAsString());
                if(!subscriptionMatches(subscriptions.get(channel),payload.getAsJsonObject("subscription")))return;
                if(!Objects.equals(broadcasters.get(channel),event.get("broadcaster_user_id").getAsString())||!desired().contains(channel))return;
                Instant at=Instant.parse(metadata.get("message_timestamp").getAsString());long now=System.currentTimeMillis();if(at.toEpochMilli()<now-120_000||at.toEpochMilli()>now+60_000)return;
                buffer.offer(channel+":"+event.get("message_id").getAsString(),channel,event.get("chatter_user_name").getAsString(),event.getAsJsonObject("message").get("text").getAsString(),now);
            }
            default -> { }
        }
    }
    private final class Connection implements WebSocket.Listener {
        final boolean handover;final long created=System.currentTimeMillis();volatile WebSocket socket;String id;volatile long last=System.currentTimeMillis(),timeout=20_000;final StringBuilder partial=new StringBuilder();
        Connection(boolean handover){this.handover=handover;}
        @Override public void onOpen(WebSocket socket){this.socket=socket;socket.request(1);}
        @Override public CompletionStage<?> onText(WebSocket socket,CharSequence text,boolean lastPart){
            try{if(partial.length()+text.length()>65_536)throw new IllegalArgumentException("Frame trop grande");partial.append(text);if(lastPart){String data=partial.toString();partial.setLength(0);if(queuedFrames.incrementAndGet()>256){queuedFrames.decrementAndGet();throw new IllegalArgumentException("File pleine");}try{manager.execute(()->{try{receive(this,JsonParser.parseString(data).getAsJsonObject());}catch(RuntimeException invalid){status="Événement Twitch refusé";socket.abort();}finally{queuedFrames.decrementAndGet();}});}catch(RejectedExecutionException stopping){queuedFrames.decrementAndGet();}}}
            catch(RuntimeException invalid){socket.abort();}finally{socket.request(1);}return null;
        }
        @Override public CompletionStage<?> onPing(WebSocket socket,ByteBuffer message){socket.request(1);return socket.sendPong(message);}
        @Override public CompletionStage<?> onPong(WebSocket socket,ByteBuffer message){socket.request(1);return null;}
        @Override public CompletionStage<?> onBinary(WebSocket socket,ByteBuffer data,boolean lastPart){socket.abort();return null;}
        @Override public CompletionStage<?> onClose(WebSocket socket,int code,String reason){disconnected();return null;}
        @Override public void onError(WebSocket socket,Throwable error){disconnected();}
        private void disconnected(){if(closed)return;try{manager.execute(()->{if(active==this){active=null;subscriptions.clear();broadcasters.clear();retryAt=System.currentTimeMillis()+2000;status="Reconnexion Twitch en cours";}if(pending==this)pending=null;});}catch(RejectedExecutionException stopping){if(!closed)status="Relais momentanément indisponible";}}
    }
    void start(){server.start();manager.scheduleWithFixedDelay(this::reconcile,0,1,TimeUnit.SECONDS);}
    int port(){return server.getAddress().getPort();}
    @Override public void close(){closed=true;server.stop(0);if(active!=null)active.socket.abort();if(pending!=null&&pending.socket!=null)pending.socket.abort();manager.shutdownNow();requests.shutdownNow();http.shutdownNow();}
    public static void main(String[] args){try{run(args);}catch(Exception refused){System.err.println("Relay refused operation; verify private configuration. No credentials logged.");System.exit(1);}}
    private static void run(String[] args)throws IOException{
        if(args.length!=1)throw new IllegalArgumentException("Indique le dossier privé Twitch");
        Path directory=Path.of(args[0]);JsonObject source=PrivateFiles.read(directory.resolve("relay-keys.json")).getAsJsonObject();Map<String,String> keys=new HashMap<>();Set<String> unique=new HashSet<>();source.entrySet().forEach(e->{String key=e.getValue().getAsString();if(!e.getKey().matches("[a-z][a-z0-9-]{0,39}")||!key.matches("[a-f0-9]{64}")||!unique.add(key))throw new IllegalArgumentException("Clé backend invalide");keys.put(e.getKey(),key);});if(keys.isEmpty()||keys.size()>64)throw new IllegalArgumentException("Nombre de backends invalide");
        JsonObject config=PrivateFiles.read(directory.resolve("bridge.json")).getAsJsonObject();
        if(!config.get("enabled").getAsBoolean())throw new IllegalArgumentException("Relais désactivé");
        TwitchBridge bridge=new TwitchBridge(directory,keys,28765);Runtime.getRuntime().addShutdownHook(new Thread(bridge::close));bridge.start();
    }
}
