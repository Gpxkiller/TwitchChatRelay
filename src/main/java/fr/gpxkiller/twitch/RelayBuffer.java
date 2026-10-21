package fr.gpxkiller.twitch;

import java.util.*;

/** Bounded in-memory messages, no disk history, no replay on first poll. */
final class RelayBuffer {
    record Message(long cursor,String channel,String author,String text,long at) { }
    record Batch(long cursor,List<Message> messages) { }
    private final ArrayDeque<Message> messages=new ArrayDeque<>();
    private final LinkedHashSet<String> seen=new LinkedHashSet<>();
    private long next;
    synchronized void offer(String id,String channel,String author,String text,long now){
        if(id.isBlank()||id.length()>160||!seen.add(id))return;
        while(seen.size()>5000)seen.remove(seen.iterator().next());
        messages.add(new Message(++next,RelayRules.channel(channel),RelayRules.plain(author,32),RelayRules.plain(text,300),now));
        trim(now);
    }
    private void trim(long now){while(!messages.isEmpty()&&(messages.size()>2000||messages.getFirst().at()<now-120_000))messages.removeFirst();}
    synchronized Batch poll(long after,Set<String> channels,long now){
        trim(now);
        if(after<0||after>next)return new Batch(next,List.of());
        List<Message> result=new ArrayList<>();long cursor=after;
        for(Message message:messages){
            if(message.cursor()<=after)continue;
            cursor=message.cursor();
            if(channels.contains(message.channel()))result.add(message);
            if(result.size()>=50)break;
        }
        return new Batch(Math.max(after,cursor),List.copyOf(result));
    }
}
