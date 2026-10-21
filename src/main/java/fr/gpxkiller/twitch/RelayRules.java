package fr.gpxkiller.twitch;

import java.util.*;

/** Immutable policy; UUIDs, never disguise names, decide private delivery. */
final class RelayRules {
    record Rule(boolean everyone, Map<UUID,String> players) {
        Rule { players=Map.copyOf(players); if(everyone&&!players.isEmpty())throw new IllegalArgumentException("Audience ambiguë"); }
        boolean allows(UUID id){return everyone||players.containsKey(id);}
    }
    private RelayRules() { }
    static String channel(String value){
        String name=value.toLowerCase(Locale.ROOT);
        for(String prefix:List.of("https://www.twitch.tv/","https://twitch.tv/"))if(name.startsWith(prefix))name=name.substring(prefix.length());
        if(!name.matches("[a-z0-9_]{1,25}"))throw new IllegalArgumentException("Indique le nom simple de la chaîne Twitch.");
        return name;
    }
    static Map<String,Rule> initial(String mode){return Map.of();}
    static String plain(String value,int max){
        StringBuilder out=new StringBuilder();
        value.codePoints().filter(c->!Character.isISOControl(c)&&Character.getType(c)!=Character.FORMAT&&c!=0xa7).limit(max).forEach(out::appendCodePoint);
        return out.toString();
    }
}
