package com.websocket;

import java.io.IOException;
import java.io.StringWriter;
import java.security.InvalidParameterException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonWriter;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

@ServerEndpoint(value = "/websocket")
public class WebSocketEndPoint {

    @OnOpen
    public void handleOpen(Session userSession) throws InterruptedException,
            IOException {

        //System.out.println("Client connected: " + userSession);
    }

    @OnClose
    public void handleClose(Session userSession) {
        
        //System.out.println("Client disconnected: " + userSession);
        
        ClientThread thread = _clients.remove(userSession);
        thread.stopSending();
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            System.err.println("Error waiting for client thread to stop: " + e.getMessage());
        }
        
        synchronized(_clients) {
            if(_clients.isEmpty()) {
                _stopMulticastListening();
            }
        }
    }
    
    @OnMessage
    public void handleMessage(String message, Session userSession)
            throws IOException, InterruptedException {

        if (message.equals("stop")) {
            handleClose(userSession);
        } else if(message.equals("start")) {

            //System.out.println("Client sent start: " + userSession);

            synchronized(_clients) {

                if(_clients.isEmpty()) {
                    _startMulticastListening();
                }

                BlockingQueue<byte[]> writeQueue = new LinkedBlockingQueue<byte[]>();
                _writeQueues.add(writeQueue);
    
                ClientThread thread = new ClientThread(userSession, writeQueue);
                thread.start();
                
                _clients.put(userSession, thread);
            }
        }

    }

    @OnError
    public void handleError(Throwable t) {
        t.printStackTrace();
    }

  private static void _loadSources() {
         
      _sources.add(new Source("BH",
          "172.16.42.86",
          "233.7.117.114",
          4042));

        _sources.add(new Source("BMR",
            "172.16.42.86",
            "233.7.117.125",
            4045));
    
        _sources.add(new Source("CNMZ3",
            "172.16.42.86",
            "233.7.117.82",
            4026));

        _sources.add(new Source("HWB",
            "172.16.42.86",
            "233.7.117.79",
            4021));

        _sources.add(new Source("LP",
            "172.16.42.86",
            "233.7.117.79",
            4020));
    
        _sources.add(new Source("ML",
            "172.16.42.86",
            "233.7.117.111",
            4039));
    
        _sources.add(new Source("MW",
            "172.16.42.86",
            "233.7.117.79",
            4024));
    
        /* TODO: is this NOAA-NWS?
        _sources.add(new Source("NN",
            "172.16.42.86",
            "233.7.117.82",
            4026));
         */
        
        _sources.add(new Source("PA",
            "172.16.42.86",
            "233.7.117.104",
            4031));
    
        _sources.add(new Source("PLC",
            "172.16.42.86",
            "233.7.117.123",
            4044));
    
        _sources.add(new Source("RM",
            "172.16.42.86",
            "233.7.117.106",
            4033));
    
        _sources.add(new Source("SCI",
            "172.16.42.86",
            "233.7.117.107",
            4034));
    
        _sources.add(new Source("SY",
            "172.16.42.86",
            "233.7.117.105",
            4032));
    
        _sources.add(new Source("SO",
            "172.16.42.86",
            "233.7.117.113",
            4041));
    
        _sources.add(new Source("SMERNS",
            "172.16.42.86",
            "233.7.117.128",
            4046));

        _sources.add(new Source("SDSC",
                "172.16.42.86",
                "233.7.117.110",
                4038));
        
        _sources.add(new Source("WS",
            "172.16.42.86",
            "233.7.117.119",
            4043));
    
        _sources.add(new Source("TP",
            "172.16.42.86",
            "233.7.117.102",
            4027));

    }

    private static void _startMulticastListening() {

        //System.out.println("start multicast listening");
        
        _sources.clear();
        _loadSources();
        
        _dispatcher = new DispatcherThread();
        _dispatcher.start();
        
        for(Source source : _sources) {
            MulticastListenerThread thread;
            try {
                thread = new MulticastListenerThread(source.host,
                        source.group,
                        source.port,
                        _multicastQueue);
            } catch (InvalidParameterException | IOException e) {
                System.err.println("Error creating multicast thread: " + e.getMessage());
                continue;
            }
            thread.start();
            _listeners.put(source.name, thread);
        }
    }
    
    private static void _stopMulticastListening() {
        
        //System.out.println("stop multicast listening");

        _dispatcher.stopDispatching();
        try {
            _dispatcher.join(5000);
        } catch(InterruptedException e) {
            System.err.println("Error joining dispatcher thread: " + e.getMessage());
            e.printStackTrace();
        }
        _dispatcher = null;

        for(MulticastListenerThread thread : _listeners.values()) {
            thread.stopListening();
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                System.err.println("Error joining multicast thread: " + e.getMessage());
                e.printStackTrace();
            }
        }
        _listeners.clear();
    
        _multicastQueue.clear();

        //System.out.println("done stopping multicast listening");
    }
    
    private static class ClientThread extends Thread {

        ClientThread(Session session, BlockingQueue<byte[]> queue) {
            _session = session;
            _queue = queue;
        }

        @Override
        public void run() {
            try {
                while (_keepSending.get()) {

                    final byte[] bytes = _queue.take();
                    final String str = new String(bytes);
                    
                    if(str.trim().isEmpty()) {
                        continue;
                    }
                    
                    //System.out.println("read " + str);
                    
                    for (String line : str.split("\n")) {
                        
                        String parts[] = line.split("[:\\-]+");
                        if(parts.length < 2) {
                            //System.err.println("WARNING: could not find name in: " +
                                //line);
                            continue;
                        }
                        
                        _data.put("Name", parts[1]);

                        for (String temp : line.split(",")) {
                            //System.out.println(temp);
                            String[] value = temp.split("=");
                            if (value.length > 1) {
                                _data.put(value[0], value[1]);
                            }
                        }
                    }
                    
                    synchronized(_session) {
                        if(_keepSending.get()) {
                            _session.getBasicRemote().sendText(
                                    dataToJson());
                        }
                    }
                }

            } catch (InvalidParameterException | IOException
                    | InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        
        public void stopSending() {
            _keepSending.set(false);
        }

        private String dataToJson() {

            JsonObjectBuilder dataJson = Json.createObjectBuilder();
            for(Map.Entry<String, String> entry: _data.entrySet()) {
                dataJson.add(entry.getKey(), entry.getValue());
            }
            
            _data.clear();

            JsonObject result = Json.createObjectBuilder().add(
                    "message", dataJson).build();
            StringWriter sw = new StringWriter();
            try (JsonWriter writer = Json.createWriter(sw)) {
                writer.write(result);
            }
            String str = sw.toString();
            //System.out.println(str);
            return str;
        }
       
        private BlockingQueue<byte[]> _queue;
        private Session _session;
        private Map<String,String> _data = new HashMap<String,String>();
        private AtomicBoolean _keepSending = new AtomicBoolean(true);
    }

    private static class DispatcherThread extends Thread {
        
        @Override
        public void run() {

            try {
                while(!_stop.get()) {
            
                    byte[] data = _multicastQueue.take();

                    //synchronized(_writeQueues) {
                        for(BlockingQueue<byte[]> writeQueue: _writeQueues) {
                            writeQueue.put(data);    
                        }
                    //}
                }
            } catch(InterruptedException e) {
                System.err.println("Interrupted exception in Dispatcher thread: " +  e.getMessage());
                e.printStackTrace();    
            }
        }

        public void stopDispatching() {
            _stop.set(true);
        }

        private AtomicBoolean _stop = new AtomicBoolean(false);
    }
    
    private static class Source {

        public Source(String name, String host, String group, int port) {
            this.name = name;
            this.host = host;
            this.group = group;
            this.port = port;
        }
        
        public String name;        
        public String host;
        public String group;
        public int port;
        
    }
    
    private static Map<Session,ClientThread> _clients = Collections
            .synchronizedMap(new HashMap<Session,ClientThread>());
    
    private static BlockingQueue<byte[]> _multicastQueue = new LinkedBlockingQueue<byte[]>();
    
    private static Map<String,MulticastListenerThread> _listeners =
            Collections.synchronizedMap(new HashMap<String,MulticastListenerThread>());

    private static Set<BlockingQueue<byte[]>> _writeQueues = Collections
            .synchronizedSet(new HashSet<BlockingQueue<byte[]>>());

    private static Set<Source> _sources = new HashSet<Source>();

    private static DispatcherThread _dispatcher;
}
