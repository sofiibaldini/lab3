// cosa fa questa classe:
// - apre una socket UDP per inviare notifiche ai client
// - dal trade recupera utente buyer e seller
// - recupera indirizzo e porta UDP dal userManager
// - invia notifica UDP a entrambi gli utenti coinvolti nel trade

package server;

import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.SocketException;
import java.net.InetAddress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import shared.Trade;
import shared.UserSession;
import server.UserManager;

// === GESTIONE NOTIFICHE UDP ============================
public class NotificationService {
    private DatagramSocket udpSocket;
    private UserManager userManager; // Aggiungi riferimento a UserManager
    
    public NotificationService(UserManager userManager) {
        this.userManager = userManager;
        try {
            udpSocket = new DatagramSocket(); 
        } catch (SocketException e) {
            System.err.println("Errore nell'apertura socket UDP: " + e.getMessage());
        }
    }
    
    // Nuovo metodo per notificare destinatari specifici (array)
    public void notifyBuyer(Trade trade) {
        notifyTradeToUser(trade, trade.getBuyer(), "buy");
    }

    public void notifySeller(Trade trade) {
        notifyTradeToUser(trade, trade.getSeller(), "sell");
    }

    private void notifyTradeToUser(Trade trade, String username, String type) {
        try {
            // Crea il messaggio di notifica
            JSONObject notification = new JSONObject();
            notification.put("notification", "tradeExecuted");
            if (type.equals("buy")) {
                notification.put("orderId", trade.getBuyOrderId());
            } else {
                notification.put("orderId", trade.getSellOrderId());
            }
            notification.put("type", type);
            notification.put("size", trade.getSize());
            notification.put("price", trade.getPrice());
            notification.put("timestamp", System.currentTimeMillis());
            
            String message = notification.toString();
            byte[] buffer = message.getBytes(StandardCharsets.UTF_8); // Codifica in UTF-8, serve? (?)
            
            // Invia al destinatario specificato
            UserSession session = userManager.getUserSession(username);

            if (session != null && session.getClientAddress() != null) {
                InetAddress clientAddress = session.getClientAddress();
                int udpPort = session.getUdpPort();

                DatagramPacket packet = new DatagramPacket(buffer, buffer.length, clientAddress, udpPort);
                udpSocket.send(packet);

            } else {
                System.err.println("Utente non connesso o sessione non valida: " + username);
            }
            
        } catch (IOException e) {
            System.err.println("Errore nell'invio notifica UDP a " + username + ": " + e.getMessage());
        }
    }
    
    public void close() {
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
    }
}
