package shared

public enum Side {
    BUY,
    SELL
}
public enum OrderType {
    LIMIT,
    MARKET
}

// i metodi delle classi sono stati messi come protected per permettere l'accesso dalle altre classi del package server ma non dall'esterno

// =======================================================   UTENTE  ========================
    
    public static class User {
        private String username;
        private String passwordHash;
        private Date registrationDate;
        
        protected User(String username, String passwordHash) {
            this.username = username;
            this.passwordHash = passwordHash;
            this.registrationDate = new Date(); // data di registrazione corrente (?)
        }
        
        // Getters and setters
        protected String getUsername() { return username; }
        protected String getPasswordHash() { return passwordHash; }
        protected Date getRegistrationDate() { return registrationDate; }
        
        protected void setRegistrationDate(Date date) { this.registrationDate = date; }
        protected void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    }
    
//=========================================================  SESSIONE UTENTE ========================

    public static class UserSession {
        private String username;
        private InetAddress clientAddress;
        private int udpPort;
        private Date loginTime;
        private final long INACTIVITY_TIMEOUT = 30 * 60 * 1000; // 30 minuti
        private Timer inactivityTimer; // TIMER INATTIVITÀ
        private Timer connectionTimer; // TIMER CONTROLLO CONNESSIONE

        protected UserSession(String username, InetAddress clientAddress, int udpPort) {
            this.username = username;
            this.clientAddress = clientAddress;
            this.loginTime = new Date();
            this.lastActivity = new Date();
            this.tcpSocket = tcpSocket;
        }
        
        // Getters
        protected String getUsername() { return username; }
        protected InetAddress getClientAddress() { return clientAddress; }
        protected Date getLoginTime() { return loginTime; }
        protected Date getLastActivity() { return lastActivity; }
        protected InetAddress getClientAddress() { return clientAddress; }
        protected int getUdpPort() { return udpPort; }
        protected void updateLastActivity() { this.lastActivity = new Date(); }
        protected Timer getInactivityTimer() { return inactivityTimer; }
        protected Timer getConnectionTimer() { return connectionTimer; }


        // setters timer inattività: prima creo il timer, chiamo questo metodo per salvarlo nella sessione
        protected void setInactivityTimer(Timer timer) { this.inactivityTimer = timer; }
        protected void setConnectionTimer(Timer timer) { this.connectionTimer = timer; }

    }
    

    
// =========================================================  ORDINE  ========================

    public static class Order {
        private String orderId;
        private Side side;
        private OrderType type;
        private double price;
        private int size;
        private int remainingSize;
        private int tempRemainingSize; 
        private String username;

        protected Order(String orderId, Side side, OrderType type,
                        double price, int size, String username) {
            this.orderId = orderId;
            this.side = side;
            this.type = type;
            this.price = price;
            this.size = size;
            this.remainingSize = size;
            this.username = username;
        }
        
        // Getters
        protected String getOrderId() { return orderId; }
        protected Side getSide() { return side; }
        protected OrderType getType() { return type; }
        protected double getPrice() { return price; }
        protected int getSize() { return size; }
        protected String getUsername() { return username; }
        protected int getRemainingSize() { return remainingSize; }
        protected int getTempRemainingSize() { return tempRemainingSize; }

        // Setters per modifica quantità
        protected void setRemainingSize(int size) { this.remainingSize = size; }
        protected void setTempRemainingSize(int size) { this.tempRemainingSize = size; }
    }
    
// =========================================================  TRADE  ========================

    public static class Trade {
        private String buyer;
        private String seller;
        private double price;
        private int size;
        private String buyOrderId;
        private String sellOrderId;
        
        protected Trade(String buyOrderId, String sellOrderId, double price, int size, String buyer, String seller) {
            this.buyer = buyer;
            this.seller = seller;
            this.price = price;
            this.size = size;
            this.buyOrderId = buyOrderId;
            this.sellOrderId = sellOrderId;
        }
        
        // Getters
        protected String getBuyer() { return buyer; }
        protected String getSeller() { return seller; }
        protected double getPrice() { return price; }
        protected int getSize() { return size; }
        protected String getBuyOrderId() { return buyOrderId; }
        protected String getSellOrderId() { return sellOrderId; }
    }
    

    //  lo tengo qua da parte per ora
        public boolean isUserLoggedIn(String username) { // vedi se serve ancora
            synchronized (this) {
                UserSession session = activeSessions.get(username);
                
                if (session == null) {
                    return false;
                }
                if (session.getClientAddress().equals(this.getClientAddress())) {
                    return true;
                }
                return false;
            }
        } 