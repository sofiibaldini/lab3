// === GESTIONE NOTIFICHE UDP ============================
public class NotificationService {
    private DatagramSocket udpSocket;
    private int udpPort = 4445;
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
        notifyTradeToUser(trade, trade.getBuyerUsername(), "buy");
    }

    public void notifySeller(Trade trade) {
        notifyTradeToUser(trade, trade.getSellerUsername(), "sell");
    }

    private void notifyTradeToUser(Trade trade, String username, String type) {
        try {
            // Crea il messaggio di notifica
            JSONObject notification = new JSONObject();
            notification.put("notification", "tradeExecuted");
            notification.put("orderId", trade.getOrderId());
            notification.put("type", type);
            notification.put("size", trade.getSize());
            notification.put("price", trade.getPrice());
            notification.put("timestamp", System.currentTimeMillis());
            
            String message = notification.toString();
            byte[] buffer = message.getBytes(StandardCharsets.UTF_8);
            
            // Invia al destinatario specificato
            UserSession session = userManager.getUserSession(username);
            if (session != null && session.getClientAddress() != null) {
                InetAddress clientAddress = session.getClientAddress();
                
                DatagramPacket packet = new DatagramPacket(
                    buffer, buffer.length, clientAddress, udpPort
                );
                
                udpSocket.send(packet);
                System.out.println("Notifica UDP inviata a: " + username + " - " + message);
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
