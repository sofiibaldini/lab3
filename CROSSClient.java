package client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import org.json.JSONObject;

import shared.JSONMessage;
import shared.OrderSide;

public class CROSSClient {
    private Socket tcpSocket;
    private Socket udpSocket;
    private PrintWriter out;
    private BufferedReader in;
    private String currentUser;
    private boolean loggedIn;
    
    public boolean connect() {
        try {
            String serverAddress = "localhost"; 
            // Leggi da config (?)
            int serverPort = 8080; // Leggi da config

            udpSocket = new DatagramSocket();
             // socket udp per notifiche
            tcpSocket = new Socket(serverAddress, serverPort);
            out = new PrintWriter(tcpSocket.getOutputStream(),
             true);
            in = new BufferedReader(new 
            InputStreamReader(tcpSocket.getInputStream()));
            
            return true;
        } catch (IOException e) {
            System.err.println("Errore di connessione: "
             + e.getMessage());
            return false;
        }
    }
    
    public boolean register(String username, String password) {
        if (!connect()) return false; 
        // tenta stabilire connessione tcp con server
        
        JSONObject request = JSONMessage.createRegisterMessage
        (username, password);
        JSONObject response = sendRequest(request);
        
        boolean success = response.getInt("response") == 100;
        disconnect();
        return success;
    }
    
    public boolean login(String username, String password) {
        if (!connect()) return false;
        
        JSONObject request = JSONMessage;
        createLoginMessage(username, password);
        JSONObject response = sendRequest(request);
        
        if (response.getInt("response") == 100) {
            currentUser = username;
            loggedIn = true;
            return true;
        }
        return false;
    }
    
    public long insertLimitOrder(String type, 
    int size, int price) {
        if (!loggedIn) return -1;
        
        OrderSide side = OrderSide.valueOf(type.toUpperCase());
        // per assicurarsi che input sia esattamente bid o ask e essere 
        // case insensitive
        JSONObject request = JSONMessage.
        createLimitOrderMessage(side, size, price);
        JSONObject response = sendRequest(request);
        
        return response.getLong("orderId");
    }
    
    public void logout() {
        if (loggedIn) {
            JSONObject request = new JSONObject();
            request.put("operation", "logout");
            request.put("values", new JSONObject()); 
            // invio un oggetto con values vuoto per consistenza
            
            sendRequest(request);
            loggedIn = false;
            currentUser = null;
        }
        disconnect();
    }
    
    private JSONObject sendRequest(JSONObject request) {
        try {
            out.println(request.toString());
            String responseStr = in.readLine();
            return new JSONObject(responseStr);
        } catch (IOException e) {
            System.err.println("Errore nell'invio della richiesta: " + e.getMessage());
            return new JSONObject().put("response", -1);
        }
    }
    
    public void disconnect() {
        try {
            if (out != null) out.close();
            if (in != null) in.close();
            if (tcpSocket != null) tcpSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public boolean isLoggedIn() {
        return loggedIn;
    }
}