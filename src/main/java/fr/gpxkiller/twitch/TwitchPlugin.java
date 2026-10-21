package fr.gpxkiller.twitch;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class TwitchPlugin extends JavaPlugin implements Listener,CommandExecutor,TabCompleter {
    private Map<String,RelayRules.Rule> rules=Map.of();
    private final Map<UUID,String> names=new HashMap<>();
    private final AtomicBoolean flight=new AtomicBoolean();
    private volatile boolean closed;
    private ExecutorService executor;
    private HttpClient http;
    private String mode,secret,epoch="",status="Connexion en préparation";
    private long cursor=-1,generation;
    private Path settings;
    @Override public void onEnable(){
        saveDefaultConfig();if(!getConfig().getBoolean("enabled",false)){getLogger().info("TwitchChatRelay désactivé : configuration nécessaire.");return;}mode=getConfig().getString("mode-id","");
        if(!mode.matches("[a-z][a-z0-9-]{0,39}"))throw new IllegalStateException("Mode Twitch non configuré");
        settings=getDataFolder().toPath().resolve("settings.yml");
        try{
            secret=PrivateFiles.read(getDataFolder().toPath().resolve(getConfig().getString("secret-file","bridge.secret"))).getAsString();
            if(!secret.matches("[a-f0-9]{64}"))throw new IOException("Clé locale invalide");
            if(Files.exists(settings))rules=load();else{rules=RelayRules.initial(mode);persist(rules);}
        }catch(IOException|RuntimeException error){getLogger().severe("Configuration Twitch refusée, données conservées ; aucun secret journalisé.");Bukkit.getPluginManager().disablePlugin(this);return;}
        for(OfflinePlayer player:Bukkit.getOfflinePlayers())if(player.getName()!=null)names.put(player.getUniqueId(),player.getName());
        Bukkit.getPluginManager().registerEvents(this,this);
        Objects.requireNonNull(getCommand("twitch")).setExecutor(this);getCommand("twitch").setTabCompleter(this);
        executor=Executors.newSingleThreadExecutor(r->new Thread(r,"twitch-chat-relay-poll"));
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).executor(executor).followRedirects(HttpClient.Redirect.NEVER).build();
        Bukkit.getScheduler().runTaskTimer(this,this::poll,20L,40L);
        getLogger().info("AUDIT Twitch chargé pour "+mode+", audience limitée par UUID ; aucune clé Twitch dans ce backend.");
    }
    @Override public void onDisable(){closed=true;Bukkit.getScheduler().cancelTasks(this);if(http!=null)http.shutdownNow();if(executor!=null)executor.shutdownNow();}
    @EventHandler public void join(PlayerJoinEvent event){names.put(event.getPlayer().getUniqueId(),event.getPlayer().getName());}
    private boolean admin(CommandSender sender){return sender instanceof ConsoleCommandSender || sender.hasPermission("twitchchatrelay.admin");}
    private Map<String,RelayRules.Rule> load()throws IOException{
        YamlConfiguration yaml=new YamlConfiguration();
        try{yaml.load(settings.toFile());}catch(org.bukkit.configuration.InvalidConfigurationException invalid){throw new IOException(invalid);}
        Map<String,RelayRules.Rule> result=new TreeMap<>();var section=yaml.getConfigurationSection("channels");
        if(section==null){if(yaml.contains("channels"))throw new IOException("Canaux invalides");return Map.of();}
        for(String key:section.getKeys(false)){
            String channel=RelayRules.channel(key);String path="channels."+key+".";
            String audience=yaml.getString(path+"audience","");
            if(!Set.of("tous","prive").contains(audience))throw new IOException("Audience invalide");
            Map<UUID,String> targets=new LinkedHashMap<>();Object raw=yaml.get(path+"players");
            if(!(raw instanceof List<?> list))throw new IOException("Destinataires invalides");
            for(Object row:list){if(!(row instanceof String value))throw new IOException("Destinataire invalide");String[] parts=value.split("\\|",2);UUID id=UUID.fromString(parts[0]);if(parts.length!=2||parts[1].isBlank()||targets.putIfAbsent(id,parts[1])!=null)throw new IOException("Identité invalide");}
            result.put(channel,new RelayRules.Rule(audience.equals("tous"),targets));
        }
        if(result.size()>8)throw new IOException("Trop de chaînes");
        return Map.copyOf(result);
    }
    private void persist(Map<String,RelayRules.Rule> value)throws IOException{
        YamlConfiguration yaml=new YamlConfiguration();yaml.createSection("channels");
        value.forEach((channel,rule)->{String path="channels."+channel+".";yaml.set(path+"audience",rule.everyone()?"tous":"prive");yaml.set(path+"players",rule.players().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->e.getKey()+"|"+e.getValue()).toList());});
        Path temporary=Files.createTempFile(Objects.requireNonNull(settings.getParent()),".settings-",".tmp");
        try{Files.writeString(temporary,yaml.saveToString(),StandardCharsets.UTF_8);Files.move(temporary,settings,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!admin(sender)){sender.sendMessage("§cRéservé aux admins de ce mode.");return true;}
        if(args.length==0||args[0].equalsIgnoreCase("aide")){sender.sendMessage("§d/twitch activer <chaine> <tous|joueur>\n§d/twitch desactiver <chaine>\n§d/twitch masquer <chaine> <joueur>\n§d/twitch statut");return true;}
        if(args.length==1&&args[0].equalsIgnoreCase("statut")){sender.sendMessage("§dTwitch : "+status);rules.forEach((channel,rule)->sender.sendMessage("§7"+channel+" : "+(rule.everyone()?"tout le monde":String.join(", ",rule.players().values()))));return true;}
        try{
            Map<String,RelayRules.Rule> next=new TreeMap<>(rules);String operation=args[0].toLowerCase(Locale.ROOT);
            if(args.length<2)throw new IllegalArgumentException("Indique une chaîne Twitch.");
            String channel=RelayRules.channel(args[1]);
            if(operation.equals("desactiver")&&args.length==2)next.remove(channel);
            else if(Set.of("activer","masquer").contains(operation)&&args.length==3){
                if(operation.equals("activer")&&args[2].equalsIgnoreCase("tous"))next.put(channel,new RelayRules.Rule(true,Map.of()));
                else{
                    List<UUID> selected=targets(args[2],sender);if(selected.isEmpty())throw new IllegalArgumentException("Joueur connu introuvable.");
                    RelayRules.Rule old=next.getOrDefault(channel,new RelayRules.Rule(false,Map.of()));
                    Map<UUID,String> targets=new LinkedHashMap<>(old.players());
                    for(UUID id:selected){if(operation.equals("masquer")){if(old.everyone())throw new IllegalArgumentException("Choisis d'abord une audience privée avec activer.");targets.remove(id);}
                    else targets.put(id,names.getOrDefault(id,id.toString()));}
                    if(targets.isEmpty())next.remove(channel);else next.put(channel,new RelayRules.Rule(false,targets));
                }
            }else throw new IllegalArgumentException("Utilise /twitch aide.");
            if(next.size()>8)throw new IllegalArgumentException("Maximum 8 chaînes sur ce mode.");
            persist(next);rules=Map.copyOf(next);generation++;cursor=-1;epoch="";
            getLogger().info("AUDIT Twitch acteur="+(sender instanceof Player p?p.getUniqueId():"console")+" action="+operation+" chaine="+channel);
            RelayRules.Rule rule=rules.get(channel);sender.sendMessage("§a"+channel+" : "+(rule==null?"désactivé":rule.everyone()?"visible par tout le monde":"visible seulement par "+String.join(", ",rule.players().values())));
        }catch(IllegalArgumentException|IOException invalid){sender.sendMessage("§cModification refusée : "+(invalid instanceof IOException?"sauvegarde impossible":invalid.getMessage()));}
        return true;
    }
    private UUID resolve(String name,CommandSender sender){
        UUID id=null;try{id=UUID.fromString(name);}catch(IllegalArgumentException ignored){for(var entry:names.entrySet())if(entry.getValue().equalsIgnoreCase(name)){id=entry.getKey();break;}}
        if(id==null||!names.containsKey(id))return null;
        Player online=Bukkit.getPlayer(id);return online!=null&&sender instanceof Player viewer&&!viewer.canSee(online)?null:id;
    }
    private List<UUID> targets(String value,CommandSender sender){
        if(!value.startsWith("@")){UUID id=resolve(value,sender);return id==null?List.of():List.of(id);}
        try{return Bukkit.selectEntities(sender,value).stream().filter(e->e instanceof Player).map(e->(Player)e).filter(p->!(sender instanceof Player viewer)||viewer.canSee(p)).map(Player::getUniqueId).distinct().toList();}
        catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Sélecteur de joueurs invalide.");}
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){
        if(!admin(sender)||args.length==0)return List.of();
        Collection<String> choices=args.length==1?List.of("activer","desactiver","masquer","statut","aide"):args.length==2?new TreeSet<>(rules.keySet()):args.length==3&&Set.of("activer","masquer").contains(args[0].toLowerCase(Locale.ROOT))?names.entrySet().stream().filter(e->resolve(e.getKey().toString(),sender)!=null).map(Map.Entry::getValue).toList():List.of();
        Set<String> result=new TreeSet<>(choices);if(args.length==3){result.addAll(List.of("@a","@p","@r","@s"));if(args[0].equalsIgnoreCase("activer"))result.add("tous");}String prefix=args[args.length-1].toLowerCase(Locale.ROOT);return result.stream().filter(s->s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
    private void poll(){
        if(closed||!flight.compareAndSet(false,true))return;
        long version=generation;JsonObject request=new JsonObject();JsonArray channels=new JsonArray();new TreeSet<>(rules.keySet()).forEach(channels::add);request.add("channels",channels);request.addProperty("after",cursor);request.addProperty("epoch",epoch);
        HttpRequest message=HttpRequest.newBuilder(URI.create("http://127.0.0.1:28765/v1/poll")).timeout(Duration.ofSeconds(4)).header("X-Twitch-Relay",secret).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(request.toString())).build();
        http.sendAsync(message,HttpResponse.BodyHandlers.ofString()).whenComplete((response,error)->{
            if(closed){flight.set(false);return;}
            try{Bukkit.getScheduler().runTask(this,()->{try{
                if(closed||generation!=version)return;
                if(error!=null||response.statusCode()!=200||response.body().length()>200_000){status="Relais momentanément indisponible";return;}
                JsonObject body=JsonParser.parseString(response.body()).getAsJsonObject();if(!mode.equals(body.get("mode").getAsString()))throw new IllegalArgumentException("Mauvais backend");
                String nextEpoch=body.get("epoch").getAsString();boolean reset=!epoch.isEmpty()&&!epoch.equals(nextEpoch);epoch=nextEpoch;cursor=body.get("cursor").getAsLong();status=body.get("status").getAsString();
                if(reset)return;
                for(JsonElement raw:body.getAsJsonArray("messages")){
                    JsonObject event=raw.getAsJsonObject();String channel=RelayRules.channel(event.get("channel").getAsString());RelayRules.Rule rule=rules.get(channel);if(rule==null)continue;
                    Component text=ChatFormat.message(channel,event.get("author").getAsString(),event.get("text").getAsString());
                    for(Player player:Bukkit.getOnlinePlayers())if(rule.allows(player.getUniqueId()))player.sendMessage(text);
                }
            }catch(RuntimeException invalid){status="Réponse du relais refusée";}finally{flight.set(false);}});}catch(org.bukkit.plugin.IllegalPluginAccessException stopping){flight.set(false);}
        });
    }
}
